import { useId } from "react";
import type { InputProps } from "../types/mainTypes";

export const Input = ({
  label,
  placeholder,
  value,
  onChange,
  name,
  type = "text",
  disabled = false,
  className = "",
  autoComplete,
  required = false,
  maxLength,
}: InputProps) => {
  // label과 input을 id로 묶어 라벨을 눌러도 입력칸에 포커스가 가게 한다(스크린리더도 이 연결을 읽는다).
  const inputId = useId();

  return (
    <div className="flex items-center gap-2">
      {/* 라벨 너비를 고정하여 정렬 유지 */}
      <label htmlFor={inputId} className="w-24 text-xs text-white shrink-0 text-right pr-2">
        {label}
      </label>
      <input
        id={inputId}
        name={name}
        type={type}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onChange?.(event.target.value)}
        disabled={disabled}
        autoComplete={autoComplete}
        required={required}
        maxLength={maxLength}
        className={`flex-1 px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${className}`}
      />
    </div>
  );
};
