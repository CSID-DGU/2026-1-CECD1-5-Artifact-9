-- 키오스크 예비분석에도 확대 히트맵과 확대 재판정 기록을 남긴다 (2026-10-29)
--
-- V11 이 진료실 분석(analysis_result)에 더한 세 컬럼을 예비분석(preliminary_analysis)에도 같은 모양으로
-- 더한다. 키오스크도 같은 FastAPI 를 부르므로 같은 확대 히트맵·재판정 결과를 받는다 — 저장하지 않으면
-- 태블릿은 방금 본 확대 히트맵을, 의사 화면은 환자가 본 것과 같은 그림을 다시 보여 줄 수 없다.
--
-- heatmap_crops   : [{rank, key, boxNorm:[x0,y0,x1,y1], peak}] — key 는 스토리지 키(gradcam_url 과 같은 방식)
-- heatmap_style   : 히트맵을 그린 방식(FastAPI HEATMAP_STYLE). 예전 행은 NULL(V11 과 같은 이유)
-- full_image_top1 : 확대 재판정을 했을 때만 {code, confidence} — 원본 사진만 보고 낸 1순위

ALTER TABLE preliminary_analysis
    ADD COLUMN heatmap_crops JSON NULL COMMENT '확대 히트맵 [{rank,key,boxNorm,peak}]',
    ADD COLUMN heatmap_style VARCHAR(40) NULL COMMENT '히트맵 표시 방식(FastAPI HEATMAP_STYLE). NULL = 이 기록 전',
    ADD COLUMN full_image_top1 JSON NULL COMMENT '확대 재판정 시 원본만 본 1순위 {code,confidence}. NULL = 재판정 안 함';
