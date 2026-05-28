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
