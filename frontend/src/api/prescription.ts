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
  diseases: PrescriptionDisease[];
  analysisId: number | null;
  prescribedAt: string;
  revisitRecommendedDate: string | null;
  doctorNotes: string | null;
  details: PrescriptionDetail[];
};

export type PrescriptionRequest = {
  diseases: Array<{ kcdDiseaseId: number; isPrimary: boolean }>;
  analysisId?: number | null;
  doctorNotes?: string | null;
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
