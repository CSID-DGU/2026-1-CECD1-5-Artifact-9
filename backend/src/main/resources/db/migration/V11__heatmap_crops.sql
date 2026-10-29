-- 히트맵 확대 보기와 확대 재판정 기록 (2026-10-28)
--
-- FastAPI 가 히트맵을 한 방식으로 통일하면서(fastapi/main.py HEATMAP_STYLE) 전체 히트맵 외에
-- 히트맵이 가장 뜨거운 곳을 최대 3곳 확대한 히트맵을 함께 돌려준다. 그리고 원본만으로는 신뢰도가
-- 낮을 때(confidence_level=low) 그 확대 영역들의 판정을 원본과 섞어 다시 판정한다(피부 병변만).
--
-- heatmap_crops   : [{rank, key, boxNorm:[x0,y0,x1,y1], peak}]
--                   key 는 스토리지 키다(heatmap_image_url 과 같은 방식 — 이미지는 DB 밖에 둔다).
--                   이 기능 전의 행은 NULL 이고, 화면은 확대 보기 없이 전체 히트맵만 보여 준다.
-- heatmap_style   : 히트맵이 어떤 방식으로 그려졌는가. 예전 행은 NULL 로 둔다 — 그 시절 방식이
--                   하나가 아니었으므로(conv_head jet → 바닥값 노랑·빨강) 어느 값으로 소급해도 거짓이 된다.
-- full_image_top1 : 확대 재판정을 했을 때만 {code, confidence} — 원본 사진만 보고 낸 1순위다.
--                   이때 predicted_disease_id·confidence 는 확대 영역까지 섞은 최종 판정이고,
--                   confidence_level 은 원본 기준 그대로다(재판정으로 신뢰도가 올라도 low 를 유지한다).
--                   NULL 은 "재판정하지 않았다" 또는 "이 기록 전의 행"이다.

ALTER TABLE analysis_result
    ADD COLUMN heatmap_crops JSON NULL COMMENT '확대 히트맵 [{rank,key,boxNorm,peak}]',
    ADD COLUMN heatmap_style VARCHAR(40) NULL COMMENT '히트맵 표시 방식(FastAPI HEATMAP_STYLE). NULL = 이 기록 전',
    ADD COLUMN full_image_top1 JSON NULL COMMENT '확대 재판정 시 원본만 본 1순위 {code,confidence}. NULL = 재판정 안 함';
