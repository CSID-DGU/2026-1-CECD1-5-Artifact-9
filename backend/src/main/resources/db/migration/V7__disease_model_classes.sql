-- AI 모델 클래스 8종을 disease 테이블에 맞춘다 (빠진 행만 채운다).
--
-- ── 왜 필요한가 ─────────────────────────────────────────────────────────────
-- disease 시드는 V1__baseline_schema.sql 의 INSERT 하나뿐인데, 그 INSERT 는
-- 이미 존재하던 DB 에서는 영영 실행되지 않는다. application.properties 에
-- baseline-on-migrate=true / baseline-version=4 가 있어서, Flyway 를 도입할 때
-- 비어 있지 않던 DB 는 "4번까지 이미 적용됨" 으로 도장을 찍고 V5 부터 시작하기
-- 때문이다(flyway_schema_history 의 첫 행이 "init 01~04 already applied").
--
-- 그래서 SCIN 데이터셋으로 8번째 클래스 'inflammatory' 를 추가한 뒤에도,
-- 그 전에 만들어진 DB 의 disease 테이블에는 HAM10000 7종만 남아 있다.
-- 모델이 inflammatory 를 1순위로 예측하는 순간 AnalysisTransactionService 의
-- findByDiseaseCode 가 빈 Optional 이 되어
--   IllegalStateException("알 수 없는 병명 코드: inflammatory")
-- 가 나고, 결과 저장 트랜잭션이 통째로 롤백된다. 추론도 히트맵 업로드도 이미
-- 끝난 뒤라서 사용자에게는 "멀쩡한 피부 사진인데 결과가 아무것도 안 나오는"
-- 것으로 보인다. 새로 만든 DB 에서는 V1 이 돌아 8행이 다 들어가므로 재현되지
-- 않는다 — 운영에서만 터진 이유가 이것이다.
--
-- ── 왜 이런 형태인가 ────────────────────────────────────────────────────────
-- disease_code 가 UNIQUE 라, 이미 있는 행은 ON DUPLICATE KEY UPDATE 의 자기
-- 대입(아무것도 바꾸지 않음)으로 지나간다. 기존 name_ko 를 덮어쓰지 않는 것이
-- 중요하다 — V5 가 latin1 이중 인코딩을 복구해 둔 결과를 되돌리면 안 된다.
-- 값은 V1 의 시드와 글자 하나까지 동일하게 두어, 새 DB(V1 이 도는 쪽)와
-- 기존 DB(이 마이그레이션이 도는 쪽)의 최종 상태가 같아지게 한다.
--
-- 앞으로 fastapi/main.py 의 CLASSES 에 클래스를 추가하면, V1 의 시드와 이
-- 목록 양쪽에 같은 코드를 넣고 새 번호의 마이그레이션을 하나 더 만든다.
INSERT INTO disease (disease_code, name_ko, name_en, severity) VALUES
  ('nv',    '멜라닌세포모반',      'Melanocytic nevus',                            'LOW'),
  ('mel',   '악성 흑색종',         'Melanoma',                                     'HIGH'),
  ('bkl',   '양성 각화증성 병변',  'Benign keratosis-like lesions',                'LOW'),
  ('bcc',   '기저세포암',          'Basal cell carcinoma',                         'HIGH'),
  ('akiec', '광선각화증/상피내암', 'Actinic keratoses / Intraepithelial carcinoma', 'MEDIUM'),
  ('df',    '피부섬유종',          'Dermatofibroma',                               'LOW'),
  ('vasc',  '혈관성 병변',         'Vascular lesions',                             'LOW'),
  -- SCIN 데이터셋으로 추가한 비색소성 염증 질환 통합 클래스
  -- (습진 / 접촉피부염 / 두드러기 / 벌레물림을 하나로 묶었다)
  ('inflammatory', '염증성 피부질환', 'Inflammatory skin condition',               'LOW')
ON DUPLICATE KEY UPDATE disease_id = disease_id;
