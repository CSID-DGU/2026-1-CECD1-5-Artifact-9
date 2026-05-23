import { apiRequest } from "./client";

export type VisitImage = {
  imageId: number;
  visitId: number;
  imageUrl: string;
  uploadedAt: string;
};

export function listVisitImages(visitId: number) {
  return apiRequest<VisitImage[]>(`/api/v1/visits/${visitId}/images`);
}

export function uploadVisitImage(visitId: number, file: File) {
  const formData = new FormData();
  formData.append("file", file);

  return apiRequest<VisitImage>(`/api/v1/visits/${visitId}/images`, {
    method: "POST",
    body: formData,
  });
}
