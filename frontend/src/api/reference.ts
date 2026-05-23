import { apiRequest } from "./client";

export type PageResponse<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};

export type KcdDisease = {
  id: number;
  code: string;
  nameKr: string;
  nameEn?: string | null;
};

export type DrugMaster = {
  id: number;
  code: string;
  nameKr: string;
  nameEn?: string | null;
};

export function searchKcdDiseases(query: string, size = 20) {
  const params = new URLSearchParams({ query, size: String(size) });
  return apiRequest<PageResponse<KcdDisease>>(
    `/api/v1/kcd-diseases?${params}`
  );
}

export function searchDrugs(query: string, size = 20) {
  const params = new URLSearchParams({ query, size: String(size) });
  return apiRequest<PageResponse<DrugMaster>>(`/api/v1/drugs?${params}`);
}
