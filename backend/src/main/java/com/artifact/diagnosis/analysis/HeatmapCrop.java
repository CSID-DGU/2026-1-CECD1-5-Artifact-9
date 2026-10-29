package com.artifact.diagnosis.analysis;

import java.util.List;

/**
 * 저장된 확대 히트맵 하나 — {@code analysis_result.heatmap_crops} JSON 의 원소.
 * 이미지는 스토리지에 두고 키만 남긴다(전체 히트맵의 heatmap_image_url 과 같은 방식).
 *
 * @param rank    1부터. 스토리지 업로드에 실패한 영역은 빠지므로 번호가 건너뛸 수 있다 —
 *                화면은 이 번호로 전체 이미지 위의 상자와 확대 히트맵을 짝짓는다.
 * @param key     스토리지 키
 * @param boxNorm 원본 이미지 기준 [x0, y0, x1, y1], 각 0~1
 * @param peak    그 영역의 히트맵 최댓값(0~1)
 * @param source  "reinfer" 또는 "zoom" — {@link FastApiCrop#source()} 그대로. 이 필드 전의 기록은 null
 */
public record HeatmapCrop(
        int rank,
        String key,
        List<Double> boxNorm,
        Double peak,
        String source
) {}
