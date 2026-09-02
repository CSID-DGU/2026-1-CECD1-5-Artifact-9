-- 예비분석(키오스크)에도 모델 버전 기록 (2026-09-02)
--
-- 배경: 확진 분석(analysis_result)은 애초에 model_version 컬럼을 갖고 있어 "이 판단이
-- 어느 모델 버전이 낸 결과인가"를 저장한다(V1 baseline). 그런데 대기실 태블릿의
-- 예비분석(preliminary_analysis)에는 같은 FastAPI 응답을 받으면서도 이 값을 버리고
-- 있었다 — 응답을 파싱하는 백엔드 record 에 필드 자체가 없었다.
--
-- 기존 행은 그 정보가 애초에 없으니 'unknown' 으로 채운다. confidence_level(V6) 때와
-- 같은 이유로 소급 백필은 하지 않는다 — 없던 값을 지금 시점 기준으로 지어내면 오히려
-- 거짓 기록이 된다.

ALTER TABLE preliminary_analysis
    ADD COLUMN model_version VARCHAR(50) NOT NULL DEFAULT 'unknown'
        COMMENT '추론에 사용된 모델 버전. 과거 행/구버전 FastAPI 응답은 unknown';
