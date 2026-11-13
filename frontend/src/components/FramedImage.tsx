import type { ReactNode } from "react";
import { useState } from "react";
import { AuthedImage } from "./AuthedImage";

type FramedImageProps = {
  /** 인증이 필요한 이미지 API 경로 */
  src: string | null | undefined;
  alt?: string;
  /** 프레임 가로(px) */
  width: number;
  /** 프레임 세로(px) */
  height: number;
  className?: string;
  imgStyle?: React.CSSProperties;
  /** 이미지가 다 받아지고 크기 계산까지 끝난 뒤 호출된다. */
  onLoad?: () => void;
  /**
   * 이미지 위에 겹쳐 그릴 요소(예: CropBoxes). 축소된 이미지와 **같은 크기**의 relative 층 안에
   * 그려지므로, 이미지 크기에 대한 비율 좌표가 프레임에 잘려도 그대로 맞는다.
   */
  children?: ReactNode;
};

/**
 * 고정 크기 프레임 안에 이미지를 표시한다.
 * - 프레임보다 작은 이미지: 확대하지 않고 원본 크기 그대로 중앙에 두고 나머지는 여백.
 * - 프레임보다 큰 이미지: 비율을 유지한 채 가로 또는 세로 중 먼저 프레임에 닿는 쪽까지
 *   축소하고(= cover 배율), 넘치는 나머지는 중앙 기준으로 잘라낸다.
 *   cover 배율이 1 이상이 되는 경우(한 변만 프레임보다 큰 이미지)는 확대하지 않고
 *   원본 크기로 중앙에 둔 채 넘치는 쪽만 잘린다.
 *
 * object-fit 으로는 "작으면 확대 안 함 + 크면 cover"를 표현할 수 없고, 겹쳐 그릴 상자의 기준이 될
 * 이미지 크기의 층도 만들 수 없어서, 로드 후 naturalWidth/Height 로 배율을 직접 계산한다.
 */
export function FramedImage({
  src, alt = "", width, height, className = "", imgStyle, onLoad, children,
}: FramedImageProps) {
  const [natural, setNatural] = useState<{ src: string; w: number; h: number } | null>(null);

  // 다른 src 의 크기를 쓰지 않는다 — 원본↔히트맵 전환 직후 한 프레임 동안 옛 크기로 그려지지 않게.
  const size =
    natural && natural.src === src
      ? (() => {
        const scale = Math.min(1, Math.max(width / natural.w, height / natural.h));
        return { width: natural.w * scale, height: natural.h * scale };
      })()
      : null;

  return (
    <div
      className={`flex shrink-0 items-center justify-center overflow-hidden bg-gray-900 ${className}`}
      style={{ width, height }}
    >
      {/* 축소된 이미지와 같은 크기의 층. 프레임보다 크면 양쪽으로 넘치고 바깥 프레임이 잘라낸다. */}
      <div
        className="relative shrink-0"
        style={{
          width: size?.width,
          height: size?.height,
          // 크기 계산 전 원본 크기로 번쩍이지 않게 숨긴다.
          visibility: size ? "visible" : "hidden",
        }}
      >
        <AuthedImage
          src={src}
          alt={alt}
          draggable={false}
          onLoad={(e) => {
            const img = e.currentTarget;
            if (src) setNatural({ src, w: img.naturalWidth, h: img.naturalHeight });
            onLoad?.();
          }}
          style={{
            display: "block",
            maxWidth: "none",
            maxHeight: "none",
            width: size ? "100%" : undefined,
            height: size ? "100%" : undefined,
            ...imgStyle,
          }}
        />
        {size && children}
      </div>
    </div>
  );
}