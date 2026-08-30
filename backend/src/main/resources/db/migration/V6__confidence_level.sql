-- AI 신뢰도 등급 기록 (2026-08-30)
--
-- 배경: 이날 FastAPI 의 신뢰도 임계값이 **차단선에서 경고선으로** 바뀌었다.
-- 예전에는 임계값 미만이면 결과를 아예 돌려주지 않았지만(HTTP 422), 이제는 결과를
-- 그대로 주고 "확신 낮음" 표시만 붙인다. 이유와 실측 근거는 fastapi/main.py 의
-- LOW_CONFIDENCE_THRESHOLD 주석에 있다.
--
-- 왜 등급을 저장하는가 — confidence 로 매번 다시 계산하면 되지 않나?
--   안 된다. 경고선은 앞으로 조정될 수 있고, 그때 과거 기록까지 함께 바뀌어 버린다.
--   "그 진료 당시 의사 화면에 경고가 붙어 있었는가"는 진료 기록의 일부라 나중에
--   소급해서 달라지면 안 된다. model_version 을 상수로 두지 않고 저장하는 것과 같은 이유다.
--
-- 값은 'low' / 'normal' 두 가지. 이 문자열은 fastapi/main.py 의 CONFIDENCE_LOW /
-- CONFIDENCE_NORMAL 과 약속된 값이며, 어긋나면 fastapi/tests/test_model_contract.py 가 잡는다.

ALTER TABLE analysis_result
    ADD COLUMN confidence_level VARCHAR(10) NOT NULL DEFAULT 'normal'
        COMMENT 'AI 신뢰도 등급: low=경고 표시됨 / normal',
    ADD CONSTRAINT chk_analysis_result_confidence_level
        CHECK (confidence_level IN ('low', 'normal'));

ALTER TABLE preliminary_analysis
    ADD COLUMN confidence_level VARCHAR(10) NOT NULL DEFAULT 'normal'
        COMMENT 'AI 신뢰도 등급: low=경고 표시됨 / normal',
    ADD CONSTRAINT chk_preliminary_analysis_confidence_level
        CHECK (confidence_level IN ('low', 'normal'));

-- ── 기존 행 소급 채우기 ──────────────────────────────────────────────────────
--
-- 기존 행에는 등급이 없다. DEFAULT 'normal' 로 두면 "확신이 낮았던 예측"까지 전부
-- 정상으로 남아 거짓이 되므로, 저장돼 있는 신뢰도로 되짚어 채운다.
--
-- 주의해서 읽을 것: 이 값은 "그때 화면에 경고가 떴다"는 뜻이 아니다. 당시에는 경고
-- 기능 자체가 없었다. 이 백필이 답하는 것은 "그 예측이 낮은 신뢰도였는가" 하나뿐이다.
--
-- 0.45 를 여기에 하드코딩하는 것은 의도적이다. 이 컬럼이 도입된 시점의 경고선이
-- 0.45 였다는 사실은 앞으로 임계값을 바꿔도 변하지 않는 과거의 사실이기 때문이다.
-- (그래서 이 숫자는 FastAPI 의 현재 설정을 따라가서는 **안 된다**.)

UPDATE analysis_result
   SET confidence_level = 'low'
 WHERE confidence < 0.45;

-- 예비분석에는 confidence 컬럼이 없고 top_k_json 배열의 0번이 Top-1 이다.
-- JSON 이 비었거나 형식이 다른 행은 조건이 NULL 이 되어 그대로 'normal' 로 남는다.
UPDATE preliminary_analysis
   SET confidence_level = 'low'
 WHERE CAST(JSON_UNQUOTE(JSON_EXTRACT(top_k_json, '$[0].confidence')) AS DECIMAL(6, 4)) < 0.45;
