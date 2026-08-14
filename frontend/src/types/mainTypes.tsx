import React from "react";

export type BtnProps = {
  children: React.ReactNode;
  type?: "primary" | "secondary";
  onClick?: () => void;
  disabled?: boolean;
  className?: string;
  buttonType?: "button" | "submit" | "reset";
};

export type InputProps = {
  label: string;
  placeholder?: string;
  value?: string;
  onChange?: (value: string) => void;
  name?: string;
  type?: string;
  disabled?: boolean;
  className?: string;
  /** 브라우저·비밀번호 관리자가 값을 채워 넣을 수 있도록 하는 힌트 (예: "username", "current-password"). */
  autoComplete?: string;
  required?: boolean;
  /** 서버의 @Size 상한과 맞춘다 — 넘겨 보내고 400을 받느니 입력 단계에서 막는 편이 낫다. */
  maxLength?: number;
};

export type CardProps = {
  title?: string;
  children: React.ReactNode;
  className?: string;
  contentClassName?: string;
};

export type TabsProps = {
  tabs: string[];
  active: string;
  setActive: (tab: string) => void;
};

export type TableProps = {
  headers: string[];
  data: React.ReactNode[][];
  getRowProps?: (row: React.ReactNode[], rowIdx: number) => React.HTMLAttributes<HTMLTableRowElement>;
};
