-- 안면부 피부질환(facial) 분석 태스크 도입 (2026-10-23)
--
-- ── 배경 ────────────────────────────────────────────────────────────────────
-- FastAPI 가 모델을 태스크별로 들게 됐다(fastapi/main.py 의 TASKS). 기존 8클래스 모델은
-- 'skin_lesion' 이라는 이름의 기본 태스크로 한 바이트도 건드리지 않고 그대로 두고,
-- AI허브 71863 「안면부 피부질환 이미지 합성 데이터」로 학습한 6클래스 모델을 'facial'
-- 태스크로 옆에 붙인다. 요청에 task 가 없으면 전과 100% 같은 동작이다.
-- 계약 원본은 docs/aihub-datasets-plan.md §3 (클래스 코드·한글 병명·필드 이름 전부).
--
-- 이 마이그레이션이 하는 일은 두 가지다.
--   1. analysis_result 에 "어느 태스크의 결과인가" 컬럼을 추가한다.
--   2. disease 에 facial 6종을 추가한다.
--
-- ── 1. analysis_result.analysis_type ────────────────────────────────────────
-- top_k_results 의 코드는 태스크마다 다른 클래스 목록을 가리킨다. 이 컬럼이 없으면 나중에
-- 저장된 행의 'normal' 이 facial 의 정상 피부라는 것을 DB 가 말할 수 없고, 화면도 어느 모델의
-- 결과를 보여주는지 표시할 수 없다.
--
-- 기존 행은 DEFAULT 'skin_lesion' 으로 소급된다. V6(confidence_level)·V9(model_version) 때는
-- 없던 값을 지어내지 않으려고 'unknown' 류로 채우거나 실제 값으로 되짚었지만, 여기는 다르다 —
-- 이 컬럼이 생기기 전에는 태스크가 skin_lesion 하나뿐이었으므로 "모든 기존 행이 skin_lesion
-- 의 결과"라는 것은 추정이 아니라 사실이다. 거짓이 되지 않는 소급은 DEFAULT 로 해도 된다.
--
-- CHECK 제약은 두지 않는다. 태스크는 늘어날 예정(burn, 후속 PR)이라 그때마다 제약을 고치는
-- 마이그레이션을 또 써야 하고, 허용 값의 원본은 어차피 FastAPI 의 TASKS 다 — 모르는 task 는
-- Spring 의 AnalysisType 역직렬화(400)와 FastAPI(400) 두 군데에서 먼저 걸려 여기까지 오지 못한다.
--
-- preliminary_analysis 에는 컬럼을 넣지 않는다. 키오스크 예비분석은 task 를 보내지 않아
-- skin_lesion 고정이다 — 태블릿 앞에는 모델을 고를 의사가 없다(KioskService.callFastApi 주석).
-- 예비분석에도 태스크가 생기면 그때 새 번호 마이그레이션으로 같은 모양의 컬럼을 추가한다.
--
-- ── 2. disease 시드 — 왜 V1 을 고치지 않는가 ────────────────────────────────
-- V7 은 "클래스를 추가하면 V1 의 시드와 새 마이그레이션 양쪽에 넣으라"고 적어 두었다.
-- 이 시점부터 그 지침을 바꾼다:
--   **새 번호 마이그레이션에만 ON DUPLICATE KEY 로 넣고, 계약 테스트
--   (fastapi/tests/test_model_contract.py [6])가 모든 마이그레이션의 disease INSERT 문을
--   합집합으로 모아 모델 클래스와 대조한다.**
--
-- 이유는 Flyway 체크섬이다. Flyway 는 적용한 마이그레이션 파일의 체크섬을 flyway_schema_history
-- 에 남기고 기동할 때마다 파일과 대조한다. V1 을 한 글자라도 고치면 V1 을 직접 실행한 DB 에서
-- "Migration checksum mismatch" 로 기동이 막힌다.
--   - Flyway 도입(2026-08-30, a76636e) 이전에 만들어진 운영 DB 는 baseline-version=4 로 도장만
--     찍혀 V1 을 실행한 적이 없으므로 걸리지 않는다. V7 이 쓰일 때 보고 있던 DB 가 이쪽이다.
--   - 그 뒤에 빈 상태에서 새로 만든 개발 DB 는 V1 을 실제로 실행했으므로 걸린다.
-- 같은 이유로 V7 파일도 고치지 않는다 — 이미 적용된 마이그레이션은 전부 체크섬에 묶여 있고,
-- V7 의 옛 지침은 이 머리말이 대신한다.
--
-- ON DUPLICATE KEY UPDATE 의 자기 대입은 V7 과 같은 뜻이다: disease_code 가 UNIQUE 라 이미 있는
-- 행(재실행·수동 선적재)은 아무것도 바꾸지 않고 지나간다.
-- 값은 docs/aihub-datasets-plan.md §3-1 의 표와 글자 하나까지 같아야 한다 — 한글 병명은
-- AnalysisService/KioskService 의 DISEASE_NAME_KO, fastapi/main.py 의 names_ko 와 계약 테스트가 대조한다.
-- severity 는 전부 LOW: 여섯 가지 모두 악성·전암 병변이 아니라서 확신도 경고는 기본 문구를 쓴다
-- (LowConfidenceCaution 의 DANGEROUS 집합은 HIGH/MEDIUM 만 본다). 'normal' 에는 KCD 가 없어
-- 프론트 KCD 선입력 매핑에서만 비워 둔다 — disease 행 자체는 모델 출력이므로 있어야 한다.

ALTER TABLE analysis_result
    ADD COLUMN analysis_type VARCHAR(20) NOT NULL DEFAULT 'skin_lesion'
        COMMENT '분석 태스크(모델): skin_lesion=피부 병변 8종 / facial=안면부 피부질환 6종';

INSERT INTO disease (disease_code, name_ko, name_en, severity) VALUES
  ('acne',      '여드름',       'Acne',                  'LOW'),
  ('atopic',    '아토피피부염', 'Atopic dermatitis',     'LOW'),
  ('normal',    '정상 피부',    'Normal skin',           'LOW'),
  ('psoriasis', '건선',         'Psoriasis',             'LOW'),
  ('rosacea',   '주사(장미증)', 'Rosacea',               'LOW'),
  ('seb_derm',  '지루피부염',   'Seborrheic dermatitis', 'LOW')
ON DUPLICATE KEY UPDATE disease_id = disease_id;
