import type { CertificateType } from "../api/certificates";
import type { Patient } from "../api/patients";
import type { Visit, VisitStatus } from "../api/visits";
import { certificateDraft, DEMO_MODEL, kioskComment, prescriptionComment } from "./cannedText";
import {
  buildAnalysis, buildCertificate, buildPreliminary, buildPrescription, dayAt, drugById, kcdByCode, kioskToken, localIso,
  plusMinutes, type DemoDB,
} from "./db";
import { DEMO_PRESETS } from "./presets";

/**
 * 데모를 시작할 때 만드는 가상 병원 기록. 이름·전화번호는 모두 지어낸 것이다(전화번호는 010-0000-xxxx).
 *   - 지난 진료 6건(진료완료): 사진·AI 분석·처방, 일부는 증명서까지 — 조회·증명서 화면에서 바로 볼 수 있다.
 *   - 오늘 대기 2건: 키오스크 예비분석을 마친 접수 1건, 아직 안 한 접수 1건.
 * 체험자는 여기에 자기 이름으로 새 환자를 접수해 처음부터 끝까지 따라가 본다(데모 안내 패널).
 */

type HistorySpec = {
  patient: number;
  daysAgo: number;
  hh: number;
  mm: number;
  memo: string;
  preset: string;
  kcd: string;
  drugs: Array<{ id: number; dosage: string; days: number; notes?: string }>;
  doctorNotes: string;
  certificate?: { type: CertificateType; purpose?: string; submitTo?: string; referralTo?: string };
  kiosk?: boolean;
};

const PATIENTS: Array<Omit<Patient, "createdAt">> = [
  { id: 1, name: "김하늘", gender: "F", birthDate: "1996-03-14", phone: "010-0000-1101", memo: null },
  { id: 2, name: "이준호", gender: "M", birthDate: "1988-11-02", phone: "010-0000-1102", memo: null },
  { id: 3, name: "박소윤", gender: "F", birthDate: "2001-07-21", phone: "010-0000-1103", memo: null },
  { id: 4, name: "최민혁", gender: "M", birthDate: "1974-05-09", phone: "010-0000-1104", memo: "고혈압 약 복용 중" },
  { id: 5, name: "정유진", gender: "F", birthDate: "1962-09-30", phone: "010-0000-1105", memo: null },
  { id: 6, name: "강시우", gender: "M", birthDate: "2014-01-18", phone: "010-0000-1106", memo: "보호자 연락처 동일" },
  { id: 7, name: "윤채원", gender: "F", birthDate: "1983-12-05", phone: "010-0000-1107", memo: null },
  { id: 8, name: "오지훈", gender: "M", birthDate: "1958-04-27", phone: "010-0000-1108", memo: null },
];

const HISTORY: HistorySpec[] = [
  { patient: 3, daysAgo: 18, hh: 14, mm: 20, memo: "양 볼·턱 여드름 악화, 생리 전 심해짐", preset: "facial-02", kcd: "L700",
    drugs: [{ id: 107, dosage: "1일 1회 취침 전 얇게 도포", days: 28 }], doctorNotes: "양 볼·턱 염증성 구진, 4주 후 재진",
    certificate: { type: "PRESCRIPTION", purpose: "약국 제출" }, kiosk: true },
  { patient: 4, daysAgo: 12, hh: 10, mm: 40, memo: "코 옆 상처가 낫지 않고 가끔 피가 남", preset: "lesion-05", kcd: "C443",
    drugs: [{ id: 19, dosage: "1일 2회 상처 부위 도포", days: 7 }], doctorNotes: "코 옆 진주빛 결절, 조직검사 위해 상급병원 의뢰",
    certificate: { type: "REFERRAL", purpose: "진료 의뢰", referralTo: "상급종합병원 피부과" } },
  { patient: 5, daysAgo: 9, hh: 16, mm: 5, memo: "양 볼 홍조와 화끈거림, 더위·음주에 악화", preset: "facial-13", kcd: "L719",
    drugs: [{ id: 82, dosage: "1일 2회 홍조 부위 도포", days: 28 }], doctorNotes: "양 볼 홍반·구진, 자외선 차단 교육",
    certificate: { type: "DIAGNOSIS", purpose: "보험 청구", submitTo: "○○화재" } },
  { patient: 6, daysAgo: 6, hh: 11, mm: 15, memo: "얼굴·목 가려움, 밤에 긁어 잠을 설침 (보호자 동반)", preset: "facial-08", kcd: "L209",
    drugs: [{ id: 290, dosage: "1일 2회 얇게 도포", days: 14 }, { id: 36, dosage: "1일 1회 1정 저녁", days: 14 }],
    doctorNotes: "얼굴·목 습진성 병변, 보습 교육", certificate: { type: "TREATMENT_CONFIRMATION", purpose: "학교 제출" } },
  { patient: 8, daysAgo: 3, hh: 15, mm: 30, memo: "눈썹·코 옆 각질과 붉은기, 2주째", preset: "facial-16", kcd: "L218",
    drugs: [{ id: 116, dosage: "1일 2회 도포", days: 21 }, { id: 747, dosage: "주 2회 샴푸로 사용", days: 28 }],
    doctorNotes: "피지 분비 부위 홍반·인설" },
  { patient: 1, daysAgo: 0, hh: -1, mm: 95, memo: "이마 여드름, 짜다가 흉터 생길까 걱정", preset: "facial-04", kcd: "L700",
    drugs: [{ id: 76, dosage: "1일 2회 세안 후 도포", days: 28 }], doctorNotes: "이마 면포·구진, 짜지 않도록 교육" },
];

/** 오늘 접수 — 지금으로부터 몇 분 전(자정을 넘기지 않게). */
function todayMinutesAgo(minutes: number): Date {
  const d = new Date(Date.now() - minutes * 60_000);
  const midnight = new Date();
  midnight.setHours(0, 5, 0, 0);
  return d < midnight ? midnight : d;
}

export function createSeed(): DemoDB {
  const db: DemoDB = {
    v: 1,
    seq: { patient: PATIENTS.length, visit: 100, image: 500, analysis: 300, prescription: 400, detail: 700, certificate: 200 },
    patients: PATIENTS.map((p) => ({ ...p, createdAt: localIso(dayAt(40, 9, 0)) })),
    visits: [],
    images: [],
    analyses: [],
    preliminaries: [],
    prescriptions: [],
    certificates: [],
    progress: { visitId: null, lookupSeen: false, waitingSeen: false },
  };
  const presetOf = (key: string) => DEMO_PRESETS.find((p) => p.key === key)!;
  const addVisit = (patientId: number, at: Date, status: VisitStatus, memo: string): Visit => {
    const visit: Visit = {
      id: ++db.seq.visit, patientId, visitDate: localIso(at), status, createdAt: localIso(at), receptionMemo: memo, kioskToken: kioskToken(),
    };
    db.visits.push(visit);
    return visit;
  };

  for (const h of HISTORY) {
    const at = h.hh < 0 ? todayMinutesAgo(h.mm) : dayAt(h.daysAgo, h.hh, h.mm);
    const visit = addVisit(h.patient, at, "COMPLETED", h.memo);
    const preset = presetOf(h.preset);
    if (h.kiosk) db.preliminaries.push(buildPreliminary(visit.id, preset, plusMinutes(visit.visitDate, 3), null));
    const image = { imageId: ++db.seq.image, visitId: visit.id, imageUrl: preset.imageUrl, uploadedAt: plusMinutes(visit.visitDate, 6), presetKey: preset.key };
    db.images.push(image);
    const analysis = buildAnalysis(++db.seq.analysis, visit.id, [image.imageId], preset, plusMinutes(visit.visitDate, 8));
    db.analyses.push(analysis);
    const kcd = kcdByCode(h.kcd)!;
    const comment = prescriptionComment([{ kcdCode: kcd.code, kcdNameKr: kcd.nameKr, isPrimary: true }]);
    const prescription = buildPrescription(++db.seq.prescription, visit.id, db.seq.detail + 1, {
      diseases: [{ kcd, isPrimary: true }],
      analysisId: analysis.analysisId,
      prescribedAt: plusMinutes(visit.visitDate, 15),
      doctorNotes: h.doctorNotes,
      aiComment: `${comment.line1}\n${comment.line2}`,
      aiCommentModel: DEMO_MODEL,
      aiCommentGeneratedAt: plusMinutes(visit.visitDate, 12),
      aiCommentEdited: false,
      details: h.drugs.map((d) => ({
        drugId: d.id, medicineName: drugById(d.id)?.nameKr ?? "직접 입력 약품", dosage: d.dosage, durationDays: d.days, notes: d.notes ?? null,
      })),
    });
    db.seq.detail += h.drugs.length;
    db.prescriptions.push(prescription);
    if (h.certificate) {
      const patient = db.patients.find((p) => p.id === h.patient)!;
      const draft = certificateDraft(h.certificate.type, {
        visitDate: visit.visitDate, diseases: [kcd.nameKr], drugs: prescription.details.map((d) => d.medicineName),
      });
      db.certificates.push(buildCertificate(++db.seq.certificate, visit, patient, prescription, {
        type: h.certificate.type, purpose: h.certificate.purpose ?? null, submitTo: h.certificate.submitTo ?? null,
        referralTo: h.certificate.referralTo ?? null, ...draft,
        aiModel: draft.opinion || draft.referralReason ? DEMO_MODEL : null, aiEdited: false,
      }, plusMinutes(visit.visitDate, 20)));
    }
  }

  // 오늘 대기 — 윤채원은 대기실 키오스크로 예비분석까지 마쳤고, 이준호는 접수만 했다.
  const waiting1 = addVisit(7, todayMinutesAgo(42), "RECEIVED", "왼쪽 팔 점이 최근 커진 것 같아 확인 희망");
  const nv = presetOf("lesion-13");
  db.preliminaries.push(buildPreliminary(waiting1.id, nv, plusMinutes(waiting1.visitDate, 4),
    kioskComment(nv.result.top5, nv.result.confidenceLevel)));
  addVisit(2, todayMinutesAgo(21), "RECEIVED", "이마·볼 붉은 반점과 각질, 2주째");

  return db;
}
