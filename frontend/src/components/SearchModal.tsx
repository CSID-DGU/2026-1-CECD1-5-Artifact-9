import { useEffect, useRef, useState } from "react";

export type SearchItem = {
  id: number;
  code: string;
  nameKr: string;
};

type Props = {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (item: SearchItem) => void;
  title: string;
  placeholder?: string;
  onSearch: (query: string, size: number) => Promise<{ content: SearchItem[] }>;
};

export function SearchModal({ isOpen, onClose, onSelect, title, placeholder, onSearch }: Props) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchItem[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isOpen) {
      setQuery("");
      setResults([]);
      setHasSearched(false);
      setTimeout(() => inputRef.current?.focus(), 60);
    }
  }, [isOpen]);

  async function handleSearch() {
    if (!query.trim()) return;
    setIsSearching(true);
    try {
      const res = await onSearch(query.trim(), 50);
      setResults(res.content);
      setHasSearched(true);
    } catch {
      setResults([]);
      setHasSearched(true);
    } finally {
      setIsSearching(false);
    }
  }

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/60" onClick={onClose} />

      {/* Modal */}
      <div className="relative z-10 w-[580px] max-h-[76vh] bg-card-bg border border-gray-600 rounded-xl shadow-2xl flex flex-col">

        {/* Header */}
        <div className="h-[40px] bg-side-bg flex items-center justify-between px-4 rounded-t-xl shrink-0 border-b border-gray-700">
          <h3 className="text-xs font-bold text-gray-200 tracking-wide">{title}</h3>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-white text-xl leading-none transition-colors"
          >
            ×
          </button>
        </div>

        {/* Search bar */}
        <div className="flex gap-2 p-3 border-b border-gray-700 shrink-0">
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
            placeholder={placeholder ?? "검색어 입력 후 Enter 또는 검색 버튼"}
            className="flex-1 px-3 py-2 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors"
          />
          <button
            onClick={handleSearch}
            disabled={isSearching || !query.trim()}
            className="px-4 py-2 bg-blue-600 hover:bg-blue-500 text-xs text-white rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed shrink-0"
          >
            {isSearching ? "검색중..." : "검색"}
          </button>
        </div>

        {/* Result list */}
        <div className="flex-1 overflow-y-auto min-h-0">
          {!hasSearched ? (
            <p className="px-4 py-10 text-xs text-gray-400 text-center">
              검색어를 입력하고 검색하세요 (예: 흑색종, 타크로리무스)
            </p>
          ) : results.length === 0 ? (
            <p className="px-4 py-10 text-xs text-gray-400 text-center">
              검색 결과가 없습니다
            </p>
          ) : (
            <table className="w-full text-left border-collapse text-xs">
              <thead className="bg-gray-950 text-gray-400 font-semibold sticky top-0">
                <tr>
                  <th className="px-4 py-2 w-24">코드</th>
                  <th className="px-4 py-2">이름</th>
                  <th className="px-4 py-2 w-14 text-center">선택</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {results.map((item) => (
                  <tr
                    key={item.id}
                    className="hover:bg-gray-800/60 transition-colors cursor-pointer"
                    onClick={() => { onSelect(item); onClose(); }}
                  >
                    <td className="px-4 py-2.5 font-mono text-blue-300">{item.code}</td>
                    <td className="px-4 py-2.5 text-gray-100">{item.nameKr}</td>
                    <td className="px-4 py-2.5 text-center">
                      <span className="inline-block whitespace-nowrap px-2 py-0.5 bg-blue-600 text-white text-[10px] rounded">선택</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {/* Footer */}
        {hasSearched && results.length > 0 && (
          <div className="px-4 py-2 border-t border-gray-700 shrink-0 bg-side-bg rounded-b-xl">
            <p className="text-[10px] text-gray-500">
              {results.length}건 표시 · 결과가 많으면 더 구체적인 검색어를 사용하세요
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
