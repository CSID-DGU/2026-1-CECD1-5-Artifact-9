import type { TableProps } from "../types/mainTypes";

export const Table = ({ headers, data }: TableProps) => {
  return (
    <div className="w-full overflow-x-auto rounded-lg">
      <table className="w-full text-left border-collapse text-xs">
        {/* 헤더 */}
        <thead className="bg-gray-950 text-gray-400 font-semibold uppercase border-b border-gray-700">
          <tr>
            {headers.map((header, idx) => (
              <th key={idx} className="px-4 py-2.5">{header}</th>
            ))}
          </tr>
        </thead>
        {/* 본문 */}
        <tbody className="divide-y divide-gray-800">
          {data.length === 0 ? (
            <tr>
              <td colSpan={headers.length} className="px-4 py-8 text-center text-gray-500">
                데이터가 없습니다.
              </td>
            </tr>
          ) : (
            data.map((row, rowIdx) => (
              <tr key={rowIdx} className="hover:bg-gray-800/50 transition-colors">
                {row.map((cell, cellIdx) => (
                  <td key={cellIdx} className="px-4 py-2 text-gray-200 align-middle">
                    {cell}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
};