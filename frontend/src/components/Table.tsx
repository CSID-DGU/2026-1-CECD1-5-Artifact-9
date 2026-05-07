import type { TableProps } from "../types/mainTypes";

export const Table = ({ headers, data }: TableProps) => {
  return (
    <div className="flex flex-col w-full">
      <div className="flex border-b border-gray-600">
        {headers.map((h) => (
          <div key={h} className="flex-1 p-2 text-xs text-gray-400">
            {h}
          </div>
        ))}
      </div>
      {data.map((row, i) => (
        <div key={i} className="flex border-b border-gray-700 hover:bg-gray-750">
          {row.map((cell, j) => (
            <div key={j} className="flex-1 p-2 text-sm text-gray-200">
              {cell}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
};