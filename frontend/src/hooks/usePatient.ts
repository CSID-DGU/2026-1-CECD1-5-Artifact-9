import { useState } from "react";

export type Patient = {
  id: string;
  name: string;
  status: "대기" | "완료";
  time: string;
};

export function usePatient() {
  const [patients, setPatients] = useState<Patient[]>([
    { id: "P0001", name: "김철수", status: "대기", time: "09:15" },
    { id: "P0002", name: "이영희", status: "대기", time: "09:30" },
    { id: "P0003", name: "최하나", status: "완료", time: "09:00" },
  ]);

  const toggleStatus = (id: string) => {
    setPatients((prev) =>
      prev.map((p) =>
        p.id === id ? { ...p, status: p.status === "대기" ? "완료" : "대기" } : p
      )
    );
  };

  return { patients, toggleStatus };
}