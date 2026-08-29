-- disease.name_ko 이중 인코딩 복구
--
-- 2026-06-02 최초 시드가 /docker-entrypoint-initdb.d 를 통해 들어갈 때
-- 클라이언트 문자셋이 latin1 이어서, UTF-8 바이트가 latin1 로 한 번 더 감싸져 저장됐다.
-- latin1 로 되돌린 뒤 utf8mb4 로 다시 해석하면 원문이 복구된다.
--
-- WHERE 절이 안전 장치다: 한글이 하나도 없는 행만 고친다.
-- 빈 DB에서 V1 이 정상 한글을 넣은 경우에는 이 UPDATE 가 한 행도 건드리지 않는다
-- (정상 한글에 이 변환을 적용하면 오히려 '???' 로 망가지기 때문에 반드시 필요하다).
UPDATE disease
   SET name_ko = CONVERT(BINARY(CONVERT(name_ko USING latin1)) USING utf8mb4)
 WHERE name_ko NOT REGEXP '[가-힣]';
