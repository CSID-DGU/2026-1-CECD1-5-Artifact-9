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

export function getPatient(patientId: number) {
  return apiRequest<Patient>(`/api/v1/patients/${patientId}`);
}
