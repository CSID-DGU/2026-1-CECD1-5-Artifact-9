-- 감열지 QR 이 가리킬 문서 열람 링크의 토큰.
--
-- ── 왜 컬럼을 새로 두는가 ───────────────────────────────────────────────────
-- 지금까지 QR 에는 /main/certificate?serialNo=2026-000006 처럼 화면 주소가 들어갔다.
-- 두 가지가 잘못이었다.
--   - /main/* 은 로그인이 필요한 라우트라, 종이를 받은 환자가 찍으면 로그인 화면만 뜬다.
--   - 발급번호는 '{연도}-{PK 6자리}' 순번이라, 한 장을 받은 사람이 앞뒤 번호를 찍어
--     남의 증명서에 닿을 수 있다.
-- 그래서 visit.kiosk_token 과 같은 방식으로, 추측할 수 없는 base62 12자 토큰을
-- 문서마다 따로 두고 그 토큰으로만 열리는 공개 열람 경로를 만든다.
-- 토큰 자체에는 환자 정보가 전혀 들어 있지 않다(난수) — QR 을 광학적으로 읽어도
-- 이름·생년월일 같은 값은 나오지 않는다.
--
-- ── 왜 발급 시각을 같이 두는가 ─────────────────────────────────────────────
-- 이 링크는 로그인 없이 열리므로 영구히 살아 있으면 곤란하다. 흘린 종이 한 장이
-- 그 문서의 영구 열람권이 된다. document.share.ttl-days(기본 7일)를 넘기면
-- 서버가 410 을 돌려주고 화면은 만료 안내만 띄운다.
-- 시각은 "마지막으로 그 문서를 감열지에 출력한 시각"이다. 다시 뽑아 준 환자에게는
-- 기간이 새로 시작되는 편이 자연스럽고, 토큰 자체는 바꾸지 않아 이미 나간 종이의
-- QR 도 같은 문서를 계속 가리킨다.
--
-- NULL 을 허용하는 이유: 이 마이그레이션 이전에 발급된 증명서와 접수에는 토큰이
-- 없다. 감열지를 다음에 출력할 때 그 자리에서 채운다(DocumentShareService).

ALTER TABLE certificate
    ADD COLUMN share_token CHAR(12) NULL
        COMMENT '발급확인증 QR 열람용 토큰 (base62, 추측 불가)',
    ADD COLUMN share_token_issued_at DATETIME NULL
        COMMENT '토큰 유효기간 기산점 = 마지막 감열지 출력 시각',
    ADD UNIQUE KEY uk_certificate_share_token (share_token);

ALTER TABLE visit
    ADD COLUMN summary_token CHAR(12) NULL
        COMMENT '진료요약서 QR 열람용 토큰 (base62, kiosk_token 과 별개)',
    ADD COLUMN summary_token_issued_at DATETIME NULL
        COMMENT '토큰 유효기간 기산점 = 마지막 감열지 출력 시각',
    ADD UNIQUE KEY uk_visit_summary_token (summary_token);
