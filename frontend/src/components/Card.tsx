import type { CardProps } from "../types/mainTypes";

export const Card = ({ title, children }: CardProps) => {
  return (
    <div className="bg-gray-800 rounded-xl p-4 flex flex-col gap-3">
      {title && <div className="text-sm font-semibold text-white">{title}</div>}
      {children}
    </div>
  );
};