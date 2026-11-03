-- 키오스크 예비분석에도 "어느 모델로 봤는가"를 남긴다 (2026-11-03)
--
-- 부스(2026-11-16~)에서 키오스크가 모델을 고르게 됐다. 방문자가 촬영 전에 '얼굴 피부'(facial, 기본)와
-- '점·피부 병변'(skin_lesion) 중 하나를 고른다. V10 이 "예비분석에도 태스크가 생기면 그때 같은 모양의
-- 컬럼을 추가한다"고 미뤄 둔 일을 여기서 한다 — analysis_result.analysis_type 과 같은 모양이다.
--
-- 그동안 키오스크가 모델을 고르지 않은 이유는 태블릿 앞에 모델을 고를 의사가 없어서였다. 방문자가 고르는 것은
-- 병명이 아니라 "무엇을 찍었는가"(얼굴인지, 점·병변인지)이고, 그것은 찍는 사람이 가장 잘 안다. 그래서 화면의
-- 선택지도 병명 목록이 아니라 촬영 대상으로 보여 준다(KioskAnalyze.tsx).
--
-- 이 컬럼이 없으면 top_k_json 의 'normal' 이 안면부 모델의 정상 피부인지 DB 가 말할 수 없고, 의사 화면도
-- 환자가 어느 모델의 결과를 봤는지 표시할 수 없다.
--
-- 기존 행은 DEFAULT 'skin_lesion' 으로 소급된다. V10 과 같은 이유로 추정이 아니라 사실이다 — 이 컬럼이 생기기
-- 전의 키오스크는 task 를 보내지 않아 FastAPI 기본값(skin_lesion)으로만 돌았다.

ALTER TABLE preliminary_analysis
    ADD COLUMN analysis_type VARCHAR(20) NOT NULL DEFAULT 'skin_lesion'
        COMMENT '분석 태스크(모델): skin_lesion=피부 병변 8종 / facial=안면부 피부질환 6종';
