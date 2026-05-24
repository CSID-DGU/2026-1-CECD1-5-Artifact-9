import { apiRequest } from "./client";

export type VisitStatus =
  | "RECEIVED"
  | "IN_PROGRESS"
  | "ANALYZING"
  | "ANALYZED"
  | "DIAGNOSED"
  | "PRESCRIBED"
  | "COMPLETED"
  | "CANCELLED";

export type Visit = {
  id: number;
  patientId: number;
  visitDate: string;
  status: VisitStatus;
  createdAt: string;
};

export function getVisit(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}`);
}

export function listVisits(status: VisitStatus) {
  return apiRequest<Visit[]>(`/api/v1/visits?status=${status}`);
}

export function startVisit(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}/start`, {
    method: "PATCH",
  });
}

export function diagnoseVisit(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}/diagnose`, {
    method: "PATCH",
  });
}

export function completeVisit(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}/complete`, {
    method: "PATCH",
  });
}
