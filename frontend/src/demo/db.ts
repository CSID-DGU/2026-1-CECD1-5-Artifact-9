import type { AnalysisResponse, AnalysisType } from "../api/analysis";
import type { CertificateDocument, CertificateResponse, CertificateType } from "../api/certificates";
import type { VisitImage } from "../api/images";
import type { PreliminaryAnalysis } from "../api/kiosk";
import type { Patient } from "../api/patients";
import type { PrescriptionResponse } from "../api/prescription";
import type { DrugMaster, KcdDisease } from "../api/reference";
import type { Visit } from "../api/visits";
import { DANGEROUS_CODES } from "./cannedText";
import drugJson from "./data/drugs.json";
import kcdJson from "./data/kcd.json";
import { DEMO_CHANGE_EVENT, DEMO_DB_KEY, DEMO_USER } from "./demoMode";
import type { DemoPreset } from "./presets";

/**
 * 데모 가짜 백엔드의 데이터와, 응답을 실제 백엔드와 같은 모양으로 조립하는 함수들.
 * 규칙은 백엔드에서 옮겨 왔다 — 질환 설명(AnalysisService.DISEASE_REASON), 낮은 확신도 경고
 * (LowConfidenceCaution), 증명서 문서(CertificateDataAssembler·CertificateType·ResidentNumberMask).
 */

export const KCD = kcdJson as KcdDisease[];
export const DRUGS = drugJson as DrugMaster[];

export type StoredImage = VisitImage & { presetKey: string };
export type StoredPreliminary = PreliminaryAnalysis & { visitId: number; presetKey: string };

export type DemoProgress = {
  /** 체험자가 직접 접수한 마지막 내원 — 안내 패널이 이 내원으로 단계를 따라간다. */
  visitId: number | null;
  lookupSeen: boolean;
  waitingSeen: boolean;
};

export type DemoDB = {
  v: 1;
  seq: { patient: number; visit: number; image: number; analysis: number; prescription: number; detail: number; certificate: number };
  patients: Patient[];
  visits: Visit[];
  images: StoredImage[];
  analyses: AnalysisResponse[];
  preliminaries: StoredPreliminary[];
  prescriptions: PrescriptionResponse[];
  certificates: CertificateResponse[];
  progress: DemoProgress;
};

export function readDb(): DemoDB | null {
  try {
    const raw = sessionStorage.getItem(DEMO_DB_KEY);
    if (!raw) return null;
    const db = JSON.parse(raw) as DemoDB;
    return db.v === 1 ? db : null;
  } catch {
    return null;
  }
}

/**
 * 저장은 바로 한다 — 요청마다 저장소에서 새로 읽어 고치므로, 응답을 늦추는 동안 다른 요청이 끼어도 서로 덮어쓰지 않는다.
 * 안내 패널에 알리는 것(notify)은 따로 둔다: 가짜 백엔드는 응답을 돌려줄 때 알려, 화면에 결과가 뜨는 때와 맞춘다.
 */
export function writeDb(db: DemoDB, notify = true): void {
  try {
    sessionStorage.setItem(DEMO_DB_KEY, JSON.stringify(db));
  } catch {
    /* 저장소가 가득 차도 이번 요청 결과는 돌려준다 */
  }
  if (notify) notifyDemoChange();
}

export function notifyDemoChange(): void {
  window.dispatchEvent(new Event(DEMO_CHANGE_EVENT));
}

/* ---------------------------------------------------------------- 시각 */

const pad = (n: number) => String(n).padStart(2, "0");

/** 백엔드 LocalDateTime 과 같은 형식(시간대 없음). */
export function localIso(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function localDate(d: Date): string {
  return localIso(d).slice(0, 10);
}

/** 오늘에서 days 일 전(음수면 뒤)의 hh:mm. */
export function dayAt(daysAgo: number, hh: number, mm: number): Date {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  d.setHours(hh, mm, 0, 0);
  return d;
}

export function plusMinutes(iso: string, minutes: number): string {
  const d = new Date(iso);
  d.setMinutes(d.getMinutes() + minutes);
  return localIso(d);
}

export function kioskToken(): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  const bytes = new Uint8Array(12);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => chars[b % chars.length]).join("");
}

export const visitNo = (id: number) => `V${String(id).padStart(5, "0")}`;

/* ---------------------------------------------------------------- 분석 */

const DISEASE_REASON: Record<string, string> = {
  nv: "균일한 색소 분포와 규칙적인 경계선이 멜라닌세포모반의 특성과 일치합니다. 대부분 양성이나 크기 변화 시 추적 관찰이 필요합니다.",
  mel: "불규칙한 색상 분포와 비대칭적 경계 패턴이 악성 흑색종의 특성과 일치합니다.",
  bkl: "각질층의 과증식 및 지루성 표면 패턴이 관찰됩니다. 양성 경과가 많으나 급격한 변화 시 조직 검사를 권장합니다.",
  bcc: "진주빛 경계와 확장된 모세혈관 패턴이 기저세포암의 특성과 일치합니다. 조기 외과적 제거가 권장됩니다.",
  akiec: "표피 세포의 이형성 패턴이 광선각화증/상피내암의 특성과 일치합니다. 전암성 병변으로 조기 치료가 중요합니다.",
  df: "피부 내 경계 명확한 섬유성 결절 패턴이 피부섬유종의 특성과 일치합니다. 양성이며 경과 관찰만으로 충분합니다.",
  vasc: "혈관 확장 및 혈관 내 이상 패턴이 감지되었습니다. 혈관 레이저 치료를 고려할 수 있습니다.",
  inflammatory: "홍반·부종·인설을 동반한 염증성 반응 패턴이 관찰됩니다. 습진·접촉피부염·두드러기·벌레물림 등이 포함되는 군으로, 색소성 종양 계열과는 구분됩니다. 원인 물질 회피와 국소 치료로 호전되는 경우가 많으나 증상이 지속되면 피부과 진료가 필요합니다.",
  acne: "면포·구진·농포 등 모낭 단위의 염증 소견이 여드름의 특성과 일치합니다. 병변 분포와 경과를 함께 확인해 주세요.",
  atopic: "홍반과 건조한 인설, 긁은 자국을 동반한 습진성 패턴이 아토피피부염의 특성과 일치합니다. 가려움의 정도와 재발 병력을 함께 확인해 주세요.",
  normal: "병변 소견이 뚜렷하지 않습니다. 모델이 병변을 놓쳤을 수 있으므로 증상이 있으면 진료가 필요합니다.",
  psoriasis: "경계가 명확한 홍반 위에 은백색 인설이 덮인 판 모양 패턴이 건선의 특성과 일치합니다. 두피·팔꿈치·무릎 등 호발 부위와 손발톱 변화를 함께 확인해 주세요.",
  rosacea: "얼굴 중앙부의 지속적인 홍조와 확장된 모세혈관, 구진 패턴이 주사(장미증)의 특성과 일치합니다. 면포가 없다는 점에서 여드름과 구분되며 열·음주·햇빛 등 악화 요인 확인이 필요합니다.",
  seb_derm: "피지 분비가 많은 부위의 홍반과 기름진 노란 인설이 지루피부염의 특성과 일치합니다. 만성 재발 경과가 흔하며 건선·아토피피부염과의 감별이 필요합니다.",
};

const CAUTION_DEFAULT = "AI 확신도가 낮은 결과입니다. 참고용으로만 사용하시고 반드시 의료진이 직접 확인해 주세요.";
const CAUTION_DANGEROUS = "AI 확신도는 낮지만 악성·전암 가능성이 제시되었습니다. "
  + "확신도가 낮다는 것이 위험하지 않다는 뜻은 아니므로, 참고용으로 넘기지 말고 반드시 직접 확인해 주세요.";

function cautionFor(preset: DemoPreset): string | null {
  const r = preset.result;
  if (r.confidenceLevel !== "low") return null;
  const dangerous = DANGEROUS_CODES.has(r.top5[0]?.diseaseCode) || (r.fullImageTop1 != null && DANGEROUS_CODES.has(r.fullImageTop1.diseaseCode));
  return dangerous ? CAUTION_DANGEROUS : CAUTION_DEFAULT;
}

export function buildAnalysis(id: number, visitId: number, imageIds: number[], preset: DemoPreset, analyzedAt: string): AnalysisResponse {
  const r = preset.result;
  return {
    analysisId: id,
    visitId,
    analyzedImageIds: imageIds,
    modelVersion: r.modelVersion,
    analysisType: preset.task as AnalysisType,
    top1: { diseaseCode: r.top5[0].diseaseCode, diseaseNameKo: r.top5[0].diseaseNameKo, confidence: r.top5[0].confidence },
    top5: r.top5.map((t) => ({ ...t, reason: DISEASE_REASON[t.diseaseCode] ?? "" })),
    confidenceLevel: r.confidenceLevel,
    caution: cautionFor(preset),
    inferenceTimeMs: r.inferenceTimeMs,
    analyzedAt,
    heatmapImageUrl: r.heatmapUrl,
    heatmapStyle: r.heatmapStyle,
    heatmapCrops: r.crops,
    fullImageTop1: r.fullImageTop1,
  };
}

export function buildPreliminary(visitId: number, preset: DemoPreset, analyzedAt: string, aiComment: string | null): StoredPreliminary {
  const r = preset.result;
  return {
    visitId,
    presetKey: preset.key,
    topK: r.top5.map(({ diseaseCode, diseaseNameKo, confidence }) => ({ diseaseCode, diseaseNameKo, confidence })),
    analysisType: preset.task as AnalysisType,
    confidenceLevel: r.confidenceLevel,
    caution: cautionFor(preset),
    gradcamUrl: r.heatmapUrl,
    aiComment,
    analyzedAt,
    heatmapStyle: r.heatmapStyle,
    heatmapCrops: r.crops,
    fullImageTop1: r.fullImageTop1,
  };
}

/* ---------------------------------------------------------------- 증명서 */

const CERT_TYPES: Record<CertificateType, { label: string; statutory: boolean; legalBasis: string | null; formCode: string | null; doctorOnly: boolean }> = {
  PRESCRIPTION: { label: "처방전", statutory: true, legalBasis: "의료법 시행규칙 제12조", formCode: "별지 제9호서식", doctorOnly: false },
  DIAGNOSIS: { label: "진단서", statutory: true, legalBasis: "의료법 시행규칙 제9조제1항", formCode: "별지 제5호의2서식", doctorOnly: true },
  TREATMENT_CONFIRMATION: { label: "진료확인서", statutory: false, legalBasis: null, formCode: null, doctorOnly: false },
  MEDICAL_OPINION: { label: "소견서", statutory: false, legalBasis: "의료법 제17조", formCode: null, doctorOnly: true },
  REFERRAL: { label: "진료의뢰서", statutory: true, legalBasis: "국민건강보험 요양급여의 기준에 관한 규칙", formCode: "별지 제4호서식", doctorOnly: true },
};

export const certTypeMeta = (type: CertificateType) => CERT_TYPES[type];

function residentNo(birthDate: string | null | undefined, gender: string | null | undefined): string {
  if (!birthDate) return "******-*******";
  const [y, m, d] = birthDate.split("-");
  const year = Number(y);
  const male = gender === "M" || gender === "MALE";
  const known = gender === "M" || gender === "F" || gender === "MALE" || gender === "FEMALE";
  const code = !known ? "*" : year < 2000 ? (male ? "1" : "2") : (male ? "3" : "4");
  return `${y.slice(2)}${m}${d}-${code}******`;
}

const genderLabel = (g: string | null | undefined) => (g === "M" || g === "MALE" ? "남" : g === "F" || g === "FEMALE" ? "여" : g ? "기타" : null);

export type IssueInput = {
  type: CertificateType;
  purpose?: string | null;
  submitTo?: string | null;
  opinion?: string | null;
  treatmentPlan?: string | null;
  referralReason?: string | null;
  remarks?: string | null;
  referralTo?: string | null;
  prescriptionValidDays?: number | null;
  aiModel?: string | null;
  aiEdited?: boolean | null;
};

/** 데모 문서임을 종이에도 남긴다 — 비고 칸이 비어 있으면 채운다. */
export const DEMO_REMARK = "데모 체험용 문서 — 법적 효력이 없습니다.";

export function buildCertificate(
  id: number, visit: Visit, patient: Patient, prescription: PrescriptionResponse | null, req: IssueInput, issuedAt: string,
): CertificateResponse {
  const meta = CERT_TYPES[req.type];
  const visitDate = visit.visitDate.slice(0, 10);
  const serialNo = `${issuedAt.slice(0, 4)}-${String(id).padStart(6, "0")}`;
  const isPrescription = req.type === "PRESCRIPTION";
  const content: CertificateDocument = {
    hospitalName: "아티팩트 피부과의원",
    hospitalAddress: null,
    hospitalPhone: null,
    hospitalRegistrationNo: null,
    hospitalSealImageUrl: "/hospital-seal.svg",
    patientName: patient.name,
    patientResidentNo: residentNo(patient.birthDate, patient.gender),
    patientGender: genderLabel(patient.gender),
    patientBirthDate: patient.birthDate ?? null,
    patientPhone: patient.phone ?? null,
    visitDate,
    treatmentPeriodFrom: visitDate,
    treatmentPeriodTo: prescription?.revisitRecommendedDate ?? visitDate,
    diseases: prescription
      ? [...prescription.diseases].sort((a, b) => Number(b.isPrimary) - Number(a.isPrimary))
        .map((d) => ({ code: d.kcdCode, name: d.kcdNameKr, primary: d.isPrimary }))
      : [],
    opinion: req.opinion ?? null,
    treatmentPlan: req.treatmentPlan ?? null,
    referralReason: req.referralReason ?? null,
    remarks: req.remarks?.trim() ? req.remarks : DEMO_REMARK,
    referralTo: req.referralTo ?? null,
    drugs: isPrescription && prescription
      ? prescription.details.map((d) => ({ name: d.medicineName, dosage: d.dosage, durationDays: d.durationDays, notes: d.notes }))
      : null,
    prescriptionValidDays: isPrescription ? (req.prescriptionValidDays && req.prescriptionValidDays > 0 ? req.prescriptionValidDays : 3) : null,
    purpose: req.purpose ?? null,
    submitTo: req.submitTo ?? null,
    doctorName: DEMO_USER.name,
    doctorLicenseNo: null,
    department: "피부과",
    issuedDate: issuedAt.slice(0, 10),
    serialNo,
    formCode: meta.formCode,
    legalBasis: meta.legalBasis,
  };
  return {
    id,
    serialNo,
    visitId: visit.id,
    patientId: patient.id,
    type: req.type,
    typeLabel: meta.label,
    statutory: meta.statutory,
    legalBasis: meta.legalBasis,
    formCode: meta.formCode,
    purpose: req.purpose ?? null,
    submitTo: req.submitTo ?? null,
    issuerName: DEMO_USER.name,
    issuerLicense: null,
    issuedAt,
    status: "ISSUED",
    voidReason: null,
    voidedAt: null,
    reissueOf: null,
    aiModel: req.aiModel ?? null,
    aiEdited: req.aiEdited ?? null,
    content,
  };
}

/* ---------------------------------------------------------------- 처방 */

export type PrescriptionInput = {
  diseases: Array<{ kcd: KcdDisease; isPrimary: boolean }>;
  analysisId: number | null;
  prescribedAt: string;
  doctorNotes: string | null;
  aiComment: string | null;
  aiCommentModel: string | null;
  aiCommentGeneratedAt: string | null;
  aiCommentEdited: boolean | null;
  details: Array<{ drugId: number | null; medicineName: string; dosage: string | null; durationDays: number | null; notes: string | null }>;
};

export function buildPrescription(id: number, visitId: number, firstDetailId: number, input: PrescriptionInput): PrescriptionResponse {
  return {
    prescriptionId: id,
    visitId,
    memberId: DEMO_USER.memberId,
    memberName: DEMO_USER.name,
    diseases: input.diseases.map(({ kcd, isPrimary }) => ({ kcdDiseaseId: kcd.id, kcdCode: kcd.code, kcdNameKr: kcd.nameKr, isPrimary })),
    analysisId: input.analysisId,
    prescribedAt: input.prescribedAt,
    revisitRecommendedDate: null,
    doctorNotes: input.doctorNotes,
    aiComment: input.aiComment,
    aiCommentModel: input.aiCommentModel,
    aiCommentGeneratedAt: input.aiCommentGeneratedAt,
    aiCommentEdited: input.aiCommentEdited,
    details: input.details.map((d, i) => ({ detailId: firstDetailId + i, ...d })),
  };
}

export const kcdByCode = (code: string) => KCD.find((k) => k.code === code) ?? null;
export const drugById = (id: number) => DRUGS.find((d) => d.id === id) ?? null;
