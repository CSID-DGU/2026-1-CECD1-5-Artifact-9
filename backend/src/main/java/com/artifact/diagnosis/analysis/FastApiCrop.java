package com.artifact.diagnosis.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * FastAPI 가 전체 히트맵에서 골라 확대한 영역 하나(fastapi/main.py run_inference 의 crops).
 *
 * 확대 영역 혼자의 판정은 FastAPI 가 내보내지 않는다 — 혼자서는 원본보다 훨씬 못 맞혀서
 * (HAM 홀드아웃: 확대 영역 54.8%, 원본 81.8%) 화면에 따로 보이면 오해를 부른다.
 *
 * @param rank          1부터. 히트맵이 가장 뜨거운 영역이 1이다.
 * @param boxNorm       원본 이미지 기준 [x0, y0, x1, y1], 각 0~1. 화면이 전체 이미지 위에 상자를 그릴 때 쓴다.
 * @param peak          그 영역의 히트맵 최댓값(0~1). 1번은 늘 1에 가깝다.
 * @param heatmapBase64 확대 영역의 히트맵 오버레이 JPEG.
 * @param source        "reinfer"(확대 영역을 모델에 다시 넣어 그림) 또는 "zoom"(전체 히트맵을 잘라 키움 —
 *                      안면부, 또는 재추론이 실패한 피부 병변). 이 필드 전의 FastAPI 는 보내지 않는다(null).
 */
public record FastApiCrop(
        int rank,
        @JsonProperty("box_norm") List<Double> boxNorm,
        Double peak,
        @JsonProperty("heatmap_base64") String heatmapBase64,
        String source
) {}
