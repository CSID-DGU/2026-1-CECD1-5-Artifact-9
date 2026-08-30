/**
 * AI 확신도가 낮을 때 결과 위에 붙는 경고 띠.
 *
 * 예전에는 확신도가 임계값에 못 미치면 서버가 결과 대신 422를 돌려줬다. 그 차단을 걷어내고
 * 이 경고로 바꾼 이유는 백엔드 LowConfidenceCaution 주석에 있다 — 요지는, 차단이 하필
 * 애매한 것부터 걷어내는데 애매한 쪽에 악성이 몰려 있었다는 것이다. 홀드아웃 2,857장에서
 * 경고가 붙는 14.4% 안에 전체 오답의 38%가 들어 있으니, 이 띠가 떴다는 것은 실제로
 * "여기는 틀렸을 확률이 두 배 넘게 높다"는 뜻이다.
 *
 * 문구(caution)를 그대로 받아 쓰고 프론트에서 조립하지 않는다. 악성 예측일 때는 문구가
 * 달라져야 하는데(“참고용으로만”을 붙이면 진짜 흑색종 경고를 흘려보내게 된다), 그 판단에
 * 필요한 심각도는 disease 테이블에만 있어서 서버만 답할 수 있다.
 *
 * caution 이 null 이면(=확신도 정상) 아무것도 그리지 않는다. 호출부에서 조건을 따로
 * 감쌀 필요가 없도록 여기서 끝낸다.
 */
export const LowConfidenceBanner = ({
  caution,
  className = "",
}: {
  caution: string | null;
  className?: string;
}) => {
  if (!caution) return null;

  return (
    <p
      className={`rounded border border-orange-500/50 bg-orange-500/15 px-2 py-1.5 text-[11px] leading-relaxed text-orange-100 ${className}`}
    >
      {/* 항상 떠 있는 노란 면책 문구와 색을 다르게 둔다 — 늘 보이는 것과 가끔 보이는 것이
          같은 색이면 "가끔"이 눈에 안 들어온다. */}
      <span className="font-semibold">확신 낮음</span> · {caution}
    </p>
  );
};
