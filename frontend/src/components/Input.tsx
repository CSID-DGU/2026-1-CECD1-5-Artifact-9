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
}: InputProps) => {
  return (
    <div className="flex items-center gap-2">
      {/* 라벨 너비를 고정하여 정렬 유지 */}
      <label className="w-24 text-xs text-white shrink-0 text-right pr-2">
        {label}
      </label>
      <input
        name={name}
        type={type}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onChange?.(event.target.value)}
        disabled={disabled}
        className={`flex-1 px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${className}`}
      />
    </div>
  );
};
