import { apiRequest } from "./client";

export type PrescriptionDetailRequest = {
  drugId?: number | null;
  medicineName: string;
  dosage?: string | null;
  durationDays?: number | null;
  notes?: string | null;
};

export type PrescriptionRequest = {
  kcdDiseaseId: number;
  analysisId?: number | null;
  revisitRecommendedDate?: string | null;
  doctorNotes?: string | null;
  details: PrescriptionDetailRequest[];
};

export type PrescriptionDetailResponse = {
  detailId: number;
  drugId?: number | null;
  medicineName: string;
  dosage?: string | null;
  durationDays?: number | null;
  notes?: string | null;
};

export type PrescriptionResponse = {
  prescriptionId: number;
  visitId: number;
  kcdDiseaseId: number;
  kcdCode: string;
  kcdNameKr: string;
  analysisId?: number | null;
  prescribedAt: string;
  revisitRecommendedDate?: string | null;
  doctorNotes?: string | null;
  details: PrescriptionDetailResponse[];
};

export function savePrescription(visitId: number, request: PrescriptionRequest) {
  return apiRequest<PrescriptionResponse>(`/api/v1/visits/${visitId}/prescription`, {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function getPrescription(visitId: number) {
  return apiRequest<PrescriptionResponse>(`/api/v1/visits/${visitId}/prescription`);
}
