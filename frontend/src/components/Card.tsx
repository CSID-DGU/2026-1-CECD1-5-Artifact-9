import type { CardProps } from "../types/mainTypes";

export const Card = ({ title, children, className = "" }: CardProps) => {
  return (
    <div 
      className={`bg-card-bg rounded-xl overflow-hidden flex flex-col shadow-sm border border-gray-700 ${className}`}
    >
      {/* 타이틀 */}
      {title && (
        <div className="h-[30px] bg-side-bg flex items-center px-4 shrink-0">
          <h3 className="text-xs font-bold text-gray-200 tracking-wide">
            {title}
          </h3>
        </div>
      )}
      
      {/* 콘텐츠 */}
      <div className="flex-1 p-4 overflow-y-auto">
        {children}
      </div>
    </div>
  );
};