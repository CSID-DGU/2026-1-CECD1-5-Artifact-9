import { apiRequest } from "./client";

export type PrescriptionDetail = {
  detailId: number;
  drugId: number | null;
  medicineName: string;
  dosage: string | null;
  durationDays: number | null;
  notes: string | null;
};

export type PrescriptionDisease = {
  kcdDiseaseId: number;
  kcdCode: string;
  kcdNameKr: string;
  isPrimary: boolean;
};

export type PrescriptionResponse = {
  prescriptionId: number;
  visitId: number;
  memberId: number;
  memberName: string;
  diseases: PrescriptionDisease[];
  analysisId: number | null;
  prescribedAt: string;
  revisitRecommendedDate: string | null;
  doctorNotes: string | null;
  aiComment: string | null;
  aiCommentModel: string | null;
  aiCommentGeneratedAt: string | null;
  aiCommentEdited: boolean | null;
  details: PrescriptionDetail[];
};

/**
 * 작성자(memberId)는 보내지 않는다 — 서버가 JWT에서 직접 꺼내 채운다.
 * 여기에 다시 넣어도 서버는 무시하므로, 화면에 보여줄 작성자는 응답의 memberId/memberName을 쓴다.
 */
export type PrescriptionRequest = {
  diseases: Array<{ kcdDiseaseId: number; isPrimary: boolean }>;
  analysisId?: number | null;
  doctorNotes?: string | null;
  aiComment?: string | null;
  aiCommentModel?: string | null;
  aiCommentGeneratedAt?: string | null;
  aiCommentEdited?: boolean | null;
  details: Array<{
    drugId?: number | null;
    medicineName: string;
    dosage?: string | null;
    durationDays?: number | null;
    notes?: string | null;
  }>;
};

export function savePrescription(visitId: number, req: PrescriptionRequest) {
  return apiRequest<PrescriptionResponse>(`/api/v1/visits/${visitId}/prescription`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function getPrescription(visitId: number) {
  return apiRequest<PrescriptionResponse>(`/api/v1/visits/${visitId}/prescription`);
}

export type PrescriptionCommentResponse = {
  line1: string;
  line2: string;
};

/**
 * 접수 메모는 보내지 않는다. 서버가 visitId 로 DB 에서 직접 읽고, 외부 모델로 나가기 전에
 * 환자 이름·연락처를 지운다. 예전에는 화면이 메모 원문을 실어 보냈고 그대로 Gemini 에 들어갔다.
 */
export function getAiPrescriptionComment(
  visitId: number,
  diseases: Array<{ kcdCode: string; kcdNameKr: string; isPrimary: boolean }>
) {
  return apiRequest<PrescriptionCommentResponse>(
    `/api/v1/visits/${visitId}/prescription/comment`,
    {
      method: "POST",
      body: JSON.stringify({ diseases }),
    }
  );
}
