import type { TabsProps } from "../types/mainTypes";

export const Tab = ({ tabs, active, setActive }: TabsProps) => {
  return (
    <div className="flex gap-2">
      {tabs.map((tab) => (
        <button
          key={tab}
          onClick={() => setActive(tab)}
          className={`px-3 py-1 rounded-lg text-sm transition-colors ${
            active === tab ? "bg-side-bg text-white" : "text-gray-400 hover:text-gray-200"
          }`}
        >
          {tab}
        </button>
      ))}
    </div>
  );
};