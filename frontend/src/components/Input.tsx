import type { InputProps } from "../types/mainTypes";

export const Input = ({ label, placeholder }: InputProps) => {
  return (
    <div className="flex flex-col gap-1">
      <label className="text-xs text-gray-400">{label}</label>
      <input
        placeholder={placeholder}
        className="px-3 py-2 rounded-lg bg-gray-800 border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500"
      />
    </div>
  );
};