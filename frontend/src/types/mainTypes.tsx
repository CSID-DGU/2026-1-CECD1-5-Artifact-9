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
};

export type CardProps = {
  title?: string;
  children: React.ReactNode;
  className?: string;
};

export type TabsProps = {
  tabs: string[];
  active: string;
  setActive: (tab: string) => void;
};

export type TableProps = {
  headers: string[];
  data: React.ReactNode[][];
};
