type ArtifactMarkProps = {
  className?: string;
  size?: number;
};

export function ArtifactMark({ className = "text-blue-200", size = 64 }: ArtifactMarkProps) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 64 64"
      fill="none"
      aria-label="Artifact"
      role="img"
    >
      <polyline
        points="8,34 22,34 27,18 34,49 39,31 56,31"
        stroke="currentColor"
        strokeWidth="2.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="8" cy="34" r="1.9" fill="currentColor" />
      <circle cx="56" cy="31" r="1.9" fill="currentColor" />
      <circle cx="34" cy="49" r="2.4" fill="currentColor" />
      <circle cx="27" cy="18" r="3.5" fill="#3B82F6" />
    </svg>
  );
}

export function ArtifactLogo() {
  return (
    <div className="flex items-center justify-center gap-4">
      <ArtifactMark />
      <div className="flex flex-col">
        <span className="font-['Space_Grotesk'] text-[34px] font-semibold leading-none tracking-normal text-white">
          Artifact
        </span>
        <span className="mt-2 text-xs font-medium text-blue-100/70">
          AI 보조 진단 시스템
        </span>
      </div>
    </div>
  );
}
