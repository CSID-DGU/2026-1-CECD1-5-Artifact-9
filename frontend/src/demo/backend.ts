import type { CertificateResponse, CertificateSummary, CertificateType } from "../api/certificates";
import type { Patient, PatientSearchResult } from "../api/patients";
import type { PageResponse } from "../api/reference";
import type { Visit, VisitStatus } from "../api/visits";
import { certificateDraft, DEMO_MODEL, kioskComment, prescriptionComment } from "./cannedText";
import {
  buildAnalysis, buildCertificate, buildPreliminary, buildPrescription, certTypeMeta, DRUGS, KCD, kioskToken, localDate, localIso,
  notifyDemoChange, readDb, visitNo, writeDb, type DemoDB, type DemoProgress, type IssueInput,
} from "./db";
import { presetFromFileName } from "./presets";
import { createSeed } from "./seed";

/**
 * 데모 가짜 백엔드. 화면이 부르는 /api 요청을 받아, 실제 백엔드와 같은 모양의 응답을 돌려준다.
 * 상태 전이·발급 조건·오류 문구는 실제 백엔드(Visit·VisitService·CertificateService 등)를 따랐다 —
 * 체험자가 운영과 같은 순서로 막히고 풀리게 하려는 것이다. 데이터는 이 탭의 sessionStorage 에만 있다.
 */

class HttpError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const sleep = (ms: number) => new Promise((r) => window.setTimeout(r, ms));
const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** 데모용 출력 응답 — 실제 프린터로 보내지 않는다는 것을 그대로 알린다(출력 실패처럼 보이지만 사실대로다). */
const DEMO_PRINT = { ok: false, detail: "데모에서는 실제로 출력하지 않습니다. 운영에서는 접수 데스크 감열 프린터로 바로 나옵니다." };

function loadDb(): DemoDB {
  const existing = readDb();
  if (existing) return existing;
  const seeded = createSeed();
  writeDb(seeded);
  return seeded;
}

/** 안내 패널이 진행 상황을 읽는다. */
export function readSnapshot() {
  const db = loadDb();
  const visit = db.progress.visitId ? db.visits.find((v) => v.id === db.progress.visitId) ?? null : null;
  const patient = visit ? db.patients.find((p) => p.id === visit.patientId) ?? null : null;
  return {
    visit,
    patientName: patient?.name ?? null,
    hasPreliminary: !!visit && db.preliminaries.some((p) => p.visitId === visit.id),
    hasAnalysis: !!visit && db.analyses.some((a) => a.visitId === visit.id),
    hasCertificate: !!visit && db.certificates.some((c) => c.visitId === visit.id),
    progress: db.progress,
  };
}

export function markProgress(patch: Partial<DemoProgress>): void {
  const db = loadDb();
  const next = { ...db.progress, ...patch };
  if (JSON.stringify(next) === JSON.stringify(db.progress)) return;
  db.progress = next;
  writeDb(db);
}

export async function handleDemoRequest(url: URL, init?: RequestInit): Promise<Response> {
  const method = (init?.method ?? "GET").toUpperCase();
  let body: unknown = null;
  if (typeof init?.body === "string") {
    try {
      body = JSON.parse(init.body);
    } catch {
      body = null;
    }
  } else if (init?.body instanceof FormData) {
    body = init.body;
  }
  try {
    const { data, delay, changed } = route(method, url, body);
    await sleep(delay ?? 120 + Math.random() * 180);
    if (changed) notifyDemoChange(); // 안내 패널 체크를 화면에 결과가 뜨는 때에 맞춘다
    return jsonResponse(data);
  } catch (e) {
    await sleep(120);
    if (e instanceof HttpError) return jsonResponse({ timestamp: localIso(new Date()), status: e.status, message: e.message }, e.status);
    console.error("[demo]", e);
    return jsonResponse({ timestamp: localIso(new Date()), status: 500, message: "데모 처리 중 오류가 발생했습니다." }, 500);
  }
}

type Result = { data: unknown; delay?: number; changed?: boolean };

function route(method: string, url: URL, body: unknown): Result {
  const db = loadDb();
  const p = url.pathname;
  const q = url.searchParams;
  let m: RegExpMatchArray | null;
  const done = (data: unknown, delay?: number, changed = false): Result => {
    if (changed) writeDb(db, false);
    return { data, delay, changed };
  };

  // ── 환자 ────────────────────────────────────────────────
  if (method === "GET" && p === "/api/v1/patients/search") {
    const name = (q.get("name") ?? "").trim();
    if (!name) throw new HttpError(400, "이름을 입력하세요.");
    const phone = (q.get("phone") ?? "").replace(/\D/g, "");
    const rows = db.patients
      .filter((x) => x.name.includes(name))
      .filter((x) => !q.get("birthDate") || x.birthDate === q.get("birthDate"))
      .filter((x) => !q.get("gender") || x.gender === q.get("gender"))
      .filter((x) => !phone || (x.phone ?? "").replace(/\D/g, "").includes(phone))
      .map<PatientSearchResult>((x) => ({
        patientId: x.id, name: x.name, birthDate: x.birthDate, gender: x.gender, phone: x.phone,
        maskedPhone: x.phone ? x.phone.replace(/^(\d{3})-(\d{3,4})-(\d{4})$/, "$1-****-$3") : null,
        lastVisitDate: lastVisitDate(db, x.id),
      }));
    return done(rows);
  }
  if (method === "GET" && (m = p.match(/^\/api\/v1\/patients\/(\d+)$/))) return done(patientOr404(db, +m[1]));
  if (method === "GET" && p === "/api/v1/patients") {
    const id = q.get("patientId");
    const name = (q.get("name") ?? "").trim();
    const visitDate = (q.get("visitDate") ?? "").trim();
    if (!id && !name && !visitDate) return done([]);
    const onDate = visitDate ? new Set(db.visits.filter((v) => v.visitDate.startsWith(visitDate)).map((v) => v.patientId)) : null;
    return done(db.patients
      .filter((x) => !id || x.id === Number(id))
      .filter((x) => !name || x.name.includes(name))
      .filter((x) => !onDate || onDate.has(x.id)));
  }
  if (method === "POST" && p === "/api/v1/patients") {
    const req = (body ?? {}) as Partial<Patient>;
    if (!req.name?.trim()) throw new HttpError(400, "name: 이름은 필수입니다.");
    const patient: Patient = {
      id: ++db.seq.patient, name: req.name.trim(), birthDate: req.birthDate || null, gender: req.gender || null,
      phone: req.phone || null, memo: req.memo || null, createdAt: localIso(new Date()),
    };
    db.patients.push(patient);
    return done(patient, undefined, true);
  }

  // ── 내원(접수) ──────────────────────────────────────────
  if (method === "GET" && p === "/api/v1/visits") {
    if (q.get("patientId")) {
      return done(db.visits.filter((v) => v.patientId === Number(q.get("patientId"))).sort((a, b) => b.visitDate.localeCompare(a.visitDate)));
    }
    if (q.get("date")) {
      return done(db.visits.filter((v) => v.visitDate.startsWith(q.get("date")!)).sort((a, b) => a.visitDate.localeCompare(b.visitDate)));
    }
    const status = (q.get("status") ?? "RECEIVED") as VisitStatus;
    return done(db.visits.filter((v) => v.status === status).sort((a, b) => a.visitDate.localeCompare(b.visitDate)));
  }
  if (method === "POST" && p === "/api/v1/visits") {
    const req = (body ?? {}) as { patientId?: number; receptionMemo?: string | null };
    patientOr404(db, Number(req.patientId));
    const at = localIso(new Date());
    const visit: Visit = {
      id: ++db.seq.visit, patientId: Number(req.patientId), visitDate: at, status: "RECEIVED", createdAt: at,
      receptionMemo: req.receptionMemo ?? null, kioskToken: kioskToken(),
    };
    db.visits.push(visit);
    db.progress = { ...db.progress, visitId: visit.id };
    return done(visit, 350, true);
  }
  if ((m = p.match(/^\/api\/v1\/visits\/(\d+)$/))) {
    const visit = visitOr404(db, +m[1]);
    if (method === "GET") return done(visit);
    if (method === "DELETE") {
      // 실제 백엔드(Visit.cancel)와 같은 규칙 — 처방 저장 전까지만 취소한다.
      if (visit.status === "ANALYZING") throw new HttpError(409, "AI 분석 중에는 취소할 수 없습니다. 분석이 끝난 뒤 다시 시도해 주세요.");
      if (visit.status === "CANCELLED") throw new HttpError(409, "이미 취소된 접수입니다.");
      if (visit.status === "PRESCRIBED" || visit.status === "COMPLETED") {
        throw new HttpError(409, `처방을 저장한 접수는 취소할 수 없습니다. 현재 상태: ${visit.status}`);
      }
      visit.status = "CANCELLED";
      return done(visit, undefined, true);
    }
  }
  if (method === "POST" && (m = p.match(/^\/api\/v1\/visits\/(\d+)\/kiosk-token$/))) {
    const visit = visitOr404(db, +m[1]);
    if (!visit.kioskToken) visit.kioskToken = kioskToken();
    return done(visit, undefined, true);
  }
  if (method === "PATCH" && (m = p.match(/^\/api\/v1\/visits\/(\d+)\/(start|diagnose|complete)$/))) {
    const visit = visitOr404(db, +m[1]);
    if (m[2] === "start") {
      if (visit.status !== "RECEIVED") throw new HttpError(409, `접수 완료 상태에서만 진료를 시작할 수 있습니다. 현재 상태: ${visit.status}`);
      visit.status = "IN_PROGRESS";
    } else if (m[2] === "diagnose") {
      if (visit.status !== "ANALYZED" && visit.status !== "IN_PROGRESS") {
        throw new HttpError(409, `진료 중 또는 AI 분석 완료 상태에서만 진단 확정이 가능합니다. 현재 상태: ${visit.status}`);
      }
      visit.status = "DIAGNOSED";
    } else {
      if (visit.status !== "PRESCRIBED") throw new HttpError(409, `처방 저장 상태에서만 완료 처리가 가능합니다. 현재 상태: ${visit.status}`);
      visit.status = "COMPLETED";
    }
    return done(visit, 250, true);
  }
  if (method === "POST" && /^\/api\/v1\/(visits\/\d+\/print\/(ticket|visit-summary)|certificates\/\d+\/print\/slip)$/.test(p)) {
    return done(DEMO_PRINT, 400);
  }

  // ── 사진 ────────────────────────────────────────────────
  if ((m = p.match(/^\/api\/v1\/visits\/(\d+)\/images$/))) {
    const visit = visitOr404(db, +m[1]);
    if (method === "GET") return done(db.images.filter((i) => i.visitId === visit.id).map(stripPreset));
    if (method === "POST") {
      if (visit.status !== "IN_PROGRESS" && visit.status !== "ANALYZED") {
        throw new HttpError(409, `이미지 업로드는 진료중(IN_PROGRESS) 또는 분석완료(ANALYZED) 상태에서만 가능합니다. 현재 상태: ${visit.status}`);
      }
      const preset = presetFromFileName(fileNameOf(body));
      if (!preset) throw new HttpError(400, "데모에서는 미리 준비된 사진만 올릴 수 있습니다. '파일 선택'에서 사진을 골라 주세요.");
      const image = { imageId: ++db.seq.image, visitId: visit.id, imageUrl: preset.imageUrl, uploadedAt: localIso(new Date()), presetKey: preset.key };
      db.images.push(image);
      return done(stripPreset(image), 500, true);
    }
  }

  // ── AI 분석(진료실) ─────────────────────────────────────
  if ((m = p.match(/^\/api\/v1\/visits\/(\d+)\/analysis$/))) {
    const visit = visitOr404(db, +m[1]);
    if (method === "GET") {
      const latest = [...db.analyses].reverse().find((a) => a.visitId === visit.id);
      if (!latest) throw new HttpError(404, "분석 결과가 없습니다.");
      return done(latest);
    }
    if (method === "POST") {
      if (visit.status !== "IN_PROGRESS" && visit.status !== "ANALYZED") {
        throw new HttpError(409, `AI 분석을 시작할 수 없는 상태입니다. 현재 상태: ${visit.status}`);
      }
      const ids = ((body as { imageIds?: number[] })?.imageIds ?? []).map(Number);
      const images = db.images.filter((i) => i.visitId === visit.id && ids.includes(i.imageId));
      if (images.length === 0) throw new HttpError(400, "분석할 이미지를 선택해 주세요.");
      const preset = presetFromFileName(`demo-${images[0].presetKey}`)!;
      const analysis = buildAnalysis(++db.seq.analysis, visit.id, images.map((i) => i.imageId), preset, localIso(new Date()));
      db.analyses.push(analysis);
      visit.status = "ANALYZED";
      return done(analysis, 1600 + Math.random() * 900, true);
    }
  }
  if (method === "GET" && (m = p.match(/^\/api\/v1\/visits\/(\d+)\/preliminary$/))) {
    const latest = [...db.preliminaries].reverse().find((x) => x.visitId === +m![1]);
    if (!latest) throw new HttpError(404, "예비분석 결과가 없습니다.");
    return done(stripPreliminary(latest));
  }

  // ── 키오스크(대기실 휴대폰) ──────────────────────────────
  if (method === "GET" && p === "/api/kiosk/pending") throw new HttpError(404, "대기 중인 접수가 없습니다.");
  if ((m = p.match(/^\/api\/kiosk\/session\/([^/]+)(\/analyze|\/comment)?$/))) {
    const token = decodeURIComponent(m[1]);
    const today = localDate(new Date());
    const visit = db.visits.find((v) => v.kioskToken === token && v.status !== "COMPLETED" && v.status !== "CANCELLED" && v.visitDate.startsWith(today));
    if (!visit) throw new HttpError(404, "유효하지 않은 QR 코드입니다.");
    if (method === "GET" && !m[2]) {
      return done({
        visitId: visit.id, patientName: patientOr404(db, visit.patientId).name, receptionNumber: visitNo(visit.id),
        analyzed: db.preliminaries.some((x) => x.visitId === visit.id),
      });
    }
    if (method === "POST" && m[2] === "/analyze") {
      const preset = presetFromFileName(fileNameOf(body));
      if (!preset) throw new HttpError(400, "데모에서는 미리 준비된 사진만 분석할 수 있습니다. '파일 선택'에서 사진을 골라 주세요.");
      const prelim = buildPreliminary(visit.id, preset, localIso(new Date()), null);
      db.preliminaries.push(prelim);
      return done(stripPreliminary(prelim), 1500 + Math.random() * 800, true);
    }
    if (method === "POST" && m[2] === "/comment") {
      const latest = [...db.preliminaries].reverse().find((x) => x.visitId === visit.id);
      if (!latest) throw new HttpError(404, "예비분석 결과가 없습니다.");
      if (!latest.aiComment) latest.aiComment = kioskComment(latest.topK, latest.confidenceLevel);
      return done({ aiComment: latest.aiComment }, 1100, true);
    }
  }

  // ── 처방 ────────────────────────────────────────────────
  if ((m = p.match(/^\/api\/v1\/visits\/(\d+)\/prescription(\/comment)?$/))) {
    const visit = visitOr404(db, +m[1]);
    if (method === "POST" && m[2]) {
      const diseases = ((body as { diseases?: Array<{ kcdCode: string; kcdNameKr: string; isPrimary: boolean }> })?.diseases) ?? [];
      if (diseases.length === 0) throw new HttpError(400, "상병코드를 하나 이상 선택해 주세요.");
      return done({ ...prescriptionComment(diseases), model: DEMO_MODEL, available: true }, 900 + Math.random() * 600);
    }
    if (method === "GET") {
      const rx = db.prescriptions.find((x) => x.visitId === visit.id);
      if (!rx) throw new HttpError(404, "처방이 없습니다.");
      return done(rx);
    }
    if (method === "POST") {
      const req = (body ?? {}) as {
        diseases?: Array<{ kcdDiseaseId: number; isPrimary: boolean }>; analysisId?: number | null; doctorNotes?: string | null;
        aiComment?: string | null; aiCommentModel?: string | null; aiCommentGeneratedAt?: string | null; aiCommentEdited?: boolean | null;
        details?: Array<{ drugId?: number | null; medicineName: string; dosage?: string | null; durationDays?: number | null; notes?: string | null }>;
      };
      if (!req.diseases?.length) throw new HttpError(400, "diseases: must not be empty");
      if (!req.details?.length) throw new HttpError(400, "details: must not be empty");
      if (!["IN_PROGRESS", "ANALYZED", "DIAGNOSED", "PRESCRIBED"].includes(visit.status)) {
        throw new HttpError(409, `처방을 저장할 수 없는 상태입니다. 현재 상태: ${visit.status}`);
      }
      const diseases = req.diseases.map((d) => {
        const kcd = KCD.find((k) => k.id === Number(d.kcdDiseaseId));
        if (!kcd) throw new HttpError(400, `KCD 코드를 찾을 수 없습니다: ${d.kcdDiseaseId}`);
        return { kcd, isPrimary: !!d.isPrimary };
      });
      const existing = db.prescriptions.findIndex((x) => x.visitId === visit.id);
      const rx = buildPrescription(existing >= 0 ? db.prescriptions[existing].prescriptionId : ++db.seq.prescription, visit.id, db.seq.detail + 1, {
        diseases,
        analysisId: req.analysisId ?? null,
        prescribedAt: localIso(new Date()),
        doctorNotes: req.doctorNotes ?? null,
        aiComment: req.aiComment ?? null,
        aiCommentModel: req.aiCommentModel ?? null,
        aiCommentGeneratedAt: req.aiCommentGeneratedAt ?? null,
        aiCommentEdited: req.aiCommentEdited ?? null,
        details: req.details.map((d) => ({
          drugId: d.drugId ?? null, medicineName: d.medicineName, dosage: d.dosage ?? null, durationDays: d.durationDays ?? null, notes: d.notes ?? null,
        })),
      });
      db.seq.detail += req.details.length;
      if (existing >= 0) db.prescriptions[existing] = rx;
      else db.prescriptions.push(rx);
      visit.status = "PRESCRIBED";
      return done(rx, 400, true);
    }
  }

  // ── KCD·약품 검색 ───────────────────────────────────────
  if (method === "GET" && (p === "/api/v1/kcd-diseases" || p === "/api/v1/drugs")) {
    const query = (q.get("query") ?? "").trim().toLowerCase();
    const size = Math.max(1, Math.min(100, Number(q.get("size") ?? 20) || 20));
    const source = p === "/api/v1/drugs" ? DRUGS : KCD;
    const hits = query
      ? source.filter((x) => x.code.toLowerCase().startsWith(query) || x.nameKr.toLowerCase().includes(query) || (x.nameEn ?? "").toLowerCase().includes(query))
      : [];
    const page: PageResponse<(typeof source)[number]> = {
      content: hits.slice(0, size), totalElements: hits.length, totalPages: Math.ceil(hits.length / size), number: 0, size,
    };
    return done(page, 200);
  }

  // ── 증명서 ──────────────────────────────────────────────
  if ((m = p.match(/^\/api\/v1\/visits\/(\d+)\/certificates(\/draft)?$/))) {
    const visit = visitOr404(db, +m[1]);
    if (method === "GET" && !m[2]) {
      return done(db.certificates.filter((c) => c.visitId === visit.id).sort((a, b) => b.issuedAt.localeCompare(a.issuedAt)).map((c) => summary(db, c)));
    }
    const req = (body ?? {}) as IssueInput;
    const type = req.type as CertificateType;
    if (!type || !certTypeMeta(type)) throw new HttpError(400, "증명서 종류를 선택해 주세요.");
    const rx = db.prescriptions.find((x) => x.visitId === visit.id) ?? null;
    if (type !== "TREATMENT_CONFIRMATION" && !rx) {
      throw new HttpError(409, `진료(처방)가 확정되지 않아 ${certTypeMeta(type).label}을(를) 발급할 수 없습니다.`);
    }
    if (method === "POST" && m[2]) {
      const draft = certificateDraft(type, {
        visitDate: visit.visitDate, diseases: rx?.diseases.map((d) => d.kcdNameKr) ?? [], drugs: rx?.details.map((d) => d.medicineName) ?? [],
        referralTo: req.referralTo ?? null,
      });
      return done({ ...draft, model: DEMO_MODEL, generated: true, message: null }, 1200 + Math.random() * 600);
    }
    if (method === "POST") {
      const cert = buildCertificate(++db.seq.certificate, visit, patientOr404(db, visit.patientId), rx, req, localIso(new Date()));
      db.certificates.push(cert);
      return done(cert, 450, true);
    }
  }
  if (method === "GET" && p === "/api/v1/certificates") {
    const patientId = Number(q.get("patientId"));
    return done(db.certificates.filter((c) => c.patientId === patientId).sort((a, b) => b.issuedAt.localeCompare(a.issuedAt)).map((c) => summary(db, c)));
  }
  if ((m = p.match(/^\/api\/v1\/certificates\/(\d+)(\/reissue|\/void)?$/))) {
    const cert = db.certificates.find((c) => c.id === +m![1]);
    if (!cert) throw new HttpError(404, `증명서를 찾을 수 없습니다: ${m[1]}`);
    if (method === "GET" && !m[2]) return done(cert);
    if (method === "POST" && m[2] === "/reissue") {
      if (cert.status === "VOID") throw new HttpError(409, "무효 처리된 증명서는 재발급할 수 없습니다.");
      const id = ++db.seq.certificate;
      const issuedAt = localIso(new Date());
      const serialNo = `${issuedAt.slice(0, 4)}-${String(id).padStart(6, "0")}`;
      const copy: CertificateResponse = { ...cert, id, serialNo, issuedAt, reissueOf: cert.id, content: { ...cert.content, serialNo, issuedDate: issuedAt.slice(0, 10) } };
      db.certificates.push(copy);
      return done(copy, 400, true);
    }
    if (method === "POST" && m[2] === "/void") {
      const reason = ((body as { reason?: string })?.reason ?? "").trim();
      if (!reason) throw new HttpError(400, "reason: 무효 사유를 입력해 주세요.");
      if (cert.status === "VOID") throw new HttpError(409, "이미 무효 처리된 증명서입니다.");
      Object.assign(cert, { status: "VOID", voidReason: reason, voidedAt: localIso(new Date()) });
      return done(cert, 300, true);
    }
  }

  if (p.startsWith("/api/public/")) throw new HttpError(404, "데모에서는 종이 QR 열람 링크를 만들지 않습니다.");
  throw new HttpError(404, `데모에서 지원하지 않는 요청입니다: ${method} ${p}`);
}

/* ---------------------------------------------------------------- helpers */

function patientOr404(db: DemoDB, id: number): Patient {
  const patient = db.patients.find((x) => x.id === id);
  if (!patient) throw new HttpError(404, `환자를 찾을 수 없습니다. id=${id}`);
  return patient;
}

function visitOr404(db: DemoDB, id: number): Visit {
  const visit = db.visits.find((v) => v.id === id);
  if (!visit) throw new HttpError(404, `접수를 찾을 수 없습니다. id=${id}`);
  return visit;
}

function lastVisitDate(db: DemoDB, patientId: number): string | null {
  const dates = db.visits.filter((v) => v.patientId === patientId && v.status !== "CANCELLED").map((v) => v.visitDate).sort();
  return dates.length ? dates[dates.length - 1].slice(0, 10) : null;
}

function fileNameOf(body: unknown): string | null {
  if (!(body instanceof FormData)) return null;
  const file = body.get("file");
  return file instanceof File ? file.name : null;
}

function stripPreset<T extends { presetKey?: string }>(x: T): Omit<T, "presetKey"> {
  const { presetKey: _presetKey, ...rest } = x;
  void _presetKey;
  return rest;
}

function stripPreliminary<T extends { presetKey?: string; visitId?: number }>(x: T) {
  const { presetKey: _presetKey, visitId: _visitId, ...rest } = x;
  void _presetKey;
  void _visitId;
  return rest;
}

function summary(db: DemoDB, c: CertificateResponse): CertificateSummary {
  return {
    id: c.id, serialNo: c.serialNo, type: c.type, typeLabel: c.typeLabel, visitId: c.visitId,
    patientName: db.patients.find((x) => x.id === c.patientId)?.name ?? null, purpose: c.purpose, submitTo: c.submitTo,
    issuerName: c.issuerName, issuedAt: c.issuedAt, status: c.status, reissueOf: c.reissueOf,
  };
}
