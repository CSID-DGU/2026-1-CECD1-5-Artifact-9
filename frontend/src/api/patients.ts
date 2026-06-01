import { apiRequest } from "./client";

export type Gender = "M" | "F" | "OTHER" | "MALE" | "FEMALE";

export type Patient = {
  id: number;
  name: string;
  birthDate?: string | null;
  gender?: Gender | null;
  phone?: string | null;
  memo?: string | null;
  createdAt?: string | null;
};

export type PatientCreateRequest = {
  name: string;
  birthDate?: string | null;
  gender?: "M" | "F" | "OTHER" | null;
  phone?: string | null;
  memo?: string | null;
};

export function getPatient(patientId: number) {
  return apiRequest<Patient>(`/api/v1/patients/${patientId}`);
}

export function createPatient(req: PatientCreateRequest) {
  return apiRequest<Patient>(`/api/v1/patients`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function searchPatients(name: string) {
  return apiRequest<Patient[]>(`/api/v1/patients?name=${encodeURIComponent(name)}`);
}

export type PatientSearchParams = {
  patientId?: number | null;
  name?: string;
  visitDate?: string;
};

export function searchPatientsByConditions(params: PatientSearchParams) {
  const query = new URLSearchParams();

  if (params.patientId != null) query.set("patientId", String(params.patientId));
  if (params.name?.trim()) query.set("name", params.name.trim());
  if (params.visitDate?.trim()) query.set("visitDate", params.visitDate.trim());

  return apiRequest<Patient[]>(`/api/v1/patients/search?${query.toString()}`);
}
