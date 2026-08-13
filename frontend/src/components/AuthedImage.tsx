import { useEffect, useState } from "react";
import { STORAGE_KEYS } from "../constants";

type AuthedImageProps = Omit<React.ImgHTMLAttributes<HTMLImageElement>, "src"> & {
  /** 인증이 필요한 이미지 API 경로. null이면 아무것도 렌더링하지 않는다. */
  src: string | null | undefined;
  /** 로딩 중/실패 시 <img> 대신 보여줄 요소. 없으면 빈 자리로 둔다. */
  fallback?: React.ReactNode;
};

/**
 * 인증이 필요한 이미지를 표시한다.
 *
 * <p>브라우저의 &lt;img src&gt;는 Authorization 헤더를 실을 수 없다. 그래서 서버가 환자 이미지를
 * permitAll로 열어두는 방식으로 굴러가고 있었는데, 그 경로는 visitId/imageId가 순차 정수라
 * 번호만 훑으면 전체 환자의 병변 사진을 수집할 수 있었다.
 *
 * <p>이제 이미지 API도 인증을 요구하므로, fetch로 Bearer 토큰을 붙여 받아온 뒤
 * Blob URL을 만들어 &lt;img&gt;에 넣는다. 만든 Blob URL은 언마운트·src 변경 시 해제한다 —
 * 해제하지 않으면 이미지 바이트가 탭이 닫힐 때까지 메모리에 남는다.
 */
export function AuthedImage({ src, fallback = null, alt = "", ...imgProps }: AuthedImageProps) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!src) {
      setObjectUrl(null);
      setFailed(false);
      return;
    }

    let cancelled = false;
    let createdUrl: string | null = null;

    setObjectUrl(null);
    setFailed(false);

    const token = localStorage.getItem(STORAGE_KEYS.TOKEN);

    fetch(src, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
      .then((response) => {
        if (!response.ok) throw new Error(`이미지 로드 실패 (${response.status})`);
        return response.blob();
      })
      .then((blob) => {
        if (cancelled) return;
        createdUrl = URL.createObjectURL(blob);
        setObjectUrl(createdUrl);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });

    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [src]);

  if (!src || failed || !objectUrl) return <>{fallback}</>;

  return <img src={objectUrl} alt={alt} {...imgProps} />;
}
