import type { BtnProps } from "../types/mainTypes";

export const Button = ({
  children,
  type = "primary",
  onClick,
  disabled = false,
  className = "",
  buttonType = "button",
}: BtnProps) => {
  const base = "px-4 py-2 rounded-lg text-sm transition-colors disabled:cursor-not-allowed disabled:opacity-50";
  const styles = {
    primary: "bg-blue-500 text-white hover:bg-blue-600",
    secondary: "border border-gray-400 text-gray-200 hover:bg-gray-800",
  };
  return (
    <button
      type={buttonType}
      onClick={onClick}
      disabled={disabled}
      className={`${base} ${styles[type]} ${className}`}
    >
      {children}
    </button>
  );
};
