import React from "react";

export type BtnProps = {
  children: React.ReactNode;
  type?: "primary" | "secondary";
};

export type InputProps = {
  label: string;
  placeholder: string;
};

export type CardProps = {
  title?: string;
  children: React.ReactNode;
};

export type TabsProps = {
  tabs: string[];
  active: string;
  setActive: (tab: string) => void;
};

export type TableProps = {
  headers: string[];
  data: (string | number)[][];
};