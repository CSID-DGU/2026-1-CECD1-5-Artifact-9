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

// 접수 화면 전용 검색 결과 타입 (최종진료일 + 마스킹 전화번호 포함)
export type PatientSearchResult = {
  patientId: number;
  name: string;
  birthDate?: string | null;
  gender?: Gender | null;
  phone?: string | null;
  maskedPhone?: string | null;
  lastVisitDate?: string | null;
};

export function searchPatientsForReception(params: {
  name: string;
  birthDate?: string;
  gender?: string;
  phone?: string;
}) {
  const query = new URLSearchParams({ name: params.name });
  if (params.birthDate) query.set("birthDate", params.birthDate);
  if (params.gender) query.set("gender", params.gender);
  if (params.phone) query.set("phone", params.phone);
  return apiRequest<PatientSearchResult[]>(`/api/v1/patients/search?${query}`);
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

  return apiRequest<Patient[]>(`/api/v1/patients?${query.toString()}`);
}
