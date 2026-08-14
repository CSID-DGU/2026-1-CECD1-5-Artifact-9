import { useEffect, useState } from "react";
import { getToken, notifySessionExpired } from "../api/session";

type AuthedImageProps = Omit<React.ImgHTMLAttributes<HTMLImageElement>, "src"> & {
  /** 인증이 필요한 이미지 API 경로. null이면 아무것도 렌더링하지 않는다. */
  src: string | null | undefined;
  /** 로딩 중/실패 시 <img> 대신 보여줄 요소. 없으면 빈 자리로 둔다. */
  fallback?: React.ReactNode;
};

/** 어느 src에 대한 결과인지 함께 들고 다닌다 — 이유는 아래 주석 참고. */
type LoadState =
  | { src: string; status: "loaded"; objectUrl: string }
  | { src: string; status: "failed" };

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
 *
 * <p><b>결과에 src를 같이 저장하는 이유.</b> 전에는 `objectUrl` 상태만 두고 effect 안에서
 * `setObjectUrl(null)`로 초기화했다. 그런데 effect는 <b>화면이 그려진 뒤에</b> 실행된다 —
 * 즉 다른 환자를 선택한 직후 한 프레임 동안 <b>이전 환자의 병변 사진이 그대로 남아 있었다.</b>
 * 지금은 저장된 결과의 src가 현재 src와 같을 때만 그리므로, 그 프레임이 생기지 않는다.
 *
 * <p><b>401은 다른 실패와 구분한다.</b> 이 컴포넌트는 apiRequest를 거치지 않고 fetch를
 * 직접 부르므로, 여기서 알려주지 않으면 <b>토큰이 만료돼도 앱이 알지 못한다.</b>
 * 진료 화면처럼 이미지만 여러 장 떠 있는 상태에서 토큰이 죽으면, 사진 자리에 회색 상자만
 * 남고 로그인 화면으로는 끝내 넘어가지 않는다.
 */
export function AuthedImage({ src, fallback = null, alt = "", ...imgProps }: AuthedImageProps) {
  const [state, setState] = useState<LoadState | null>(null);

  useEffect(() => {
    if (!src) return;

    let cancelled = false;
    let createdUrl: string | null = null;
    const token = getToken();

    fetch(src, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
      .then((response) => {
        if (!response.ok) {
          // 토큰이 있었는데 401이면 세션이 끝난 것이다. 전역에 알려 로그아웃까지 이어지게 한다.
          if (response.status === 401 && token) notifySessionExpired();
          throw new Error(`이미지 로드 실패 (${response.status})`);
        }
        return response.blob();
      })
      .then((blob) => {
        if (cancelled) return;
        createdUrl = URL.createObjectURL(blob);
        setState({ src, status: "loaded", objectUrl: createdUrl });
      })
      .catch(() => {
        if (!cancelled) setState({ src, status: "failed" });
      });

    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [src]);

  // 지금 요청 중인 src의 결과가 아니면 아직 로딩 중인 것으로 본다.
  if (!src || state?.src !== src || state.status !== "loaded") {
    return <>{fallback}</>;
  }

  return <img src={state.objectUrl} alt={alt} {...imgProps} />;
}
