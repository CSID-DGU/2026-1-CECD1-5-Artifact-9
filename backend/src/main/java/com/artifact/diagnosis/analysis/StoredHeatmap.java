package com.artifact.diagnosis.analysis;

import java.util.List;

/**
 * 스토리지에 올린 히트맵들. 업로드는 트랜잭션 밖에서 끝내고 키만 DB 쓰기 구간으로 넘긴다.
 * 진료실 분석(AnalysisTransactionService)과 키오스크 예비분석(KioskTransactionService)이 같이 쓴다.
 *
 * @param key   전체 히트맵 키. 히트맵이 없으면 null
 * @param style 히트맵을 그린 방식(FastAPI HEATMAP_STYLE). 구버전 FastAPI 는 null
 * @param crops 올리는 데 성공한 확대 히트맵들. 없으면 빈 목록(null 아님)
 */
public record StoredHeatmap(String key, String style, List<HeatmapCrop> crops) {

    /** DB 에 넣을 값 — 확대 히트맵이 없으면 빈 배열이 아니라 NULL 로 둔다(이 기능 전의 행과 같은 모양). */
    public List<HeatmapCrop> cropsOrNull() {
        return crops.isEmpty() ? null : crops;
    }
}
