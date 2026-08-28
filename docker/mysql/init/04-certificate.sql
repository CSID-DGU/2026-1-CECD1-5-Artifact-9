USE artifact_db;

-- ---------------------------------------------------------------------
-- 12. 제증명 발급대장 (certificate)
--
--     실제 병원의 "제증명 발급대장"에 해당한다. 누가·언제·무슨 목적으로·어떤 서류를
--     발급받아 갔는지가 남아야 하고, 발급한 서류의 내용 자체도 보존되어야 한다.
--
--     content_json 이 그 보존분이다. 발급 시점의 문서 필드 전체를 스냅샷으로 굳혀둔다.
--     진단서를 발급한 뒤 의사가 처방을 수정해도(재처방은 기존 처방을 대체한다),
--     이미 환자 손에 나간 종이와 시스템이 보여주는 내용이 달라지면 안 되기 때문이다.
--     재발급은 이 스냅샷을 그대로 다시 출력하는 것이지, 지금 데이터로 다시 만드는 것이 아니다.
--
--     잘못 발급한 서류는 지우지 않고 status='VOID' 로 무효화한다 — 진료기록 원본 보존 원칙.
--
--     IF NOT EXISTS 인 이유: 이 파일은 신규 볼륨 초기화(docker-entrypoint-initdb.d)뿐 아니라
--     이미 데이터가 있는 운영 DB 에도 배포 때마다 실행된다(.github/workflows/deploy.yml).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS certificate (
    certificate_id  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '증명서ID (PK)',

    -- 발급번호. INSERT 로 PK를 받은 뒤 '{연도}-{PK 6자리}' 로 채운다(CertificateService 참고).
    -- 채번 테이블이나 MAX()+1 을 쓰지 않는 이유는 동시 발급 시 번호가 겹치기 때문이다.
    serial_no       VARCHAR(30)  NULL                    COMMENT '발급번호 (예: 2026-000042)',

    visit_id        BIGINT       NOT NULL                COMMENT '접수ID (FK)',
    patient_id      BIGINT       NOT NULL                COMMENT '환자ID (FK) — 환자별 발급이력 조회용',
    type            VARCHAR(30)  NOT NULL                COMMENT '서류 종류 (CertificateType)',

    purpose         VARCHAR(200) NULL                    COMMENT '용도 — 법정서식(진단서)의 필수 기재 항목',
    submit_to       VARCHAR(200) NULL                    COMMENT '제출처 (보험사/회사/상급병원 등)',

    -- 발급자 스냅샷. 계정이 나중에 수정·삭제돼도 발급 당시의 서명 주체는 그대로 남아야 한다.
    -- prescription.member_name 스냅샷과 같은 이유다.
    issued_by       BIGINT       NOT NULL                COMMENT '발급 요청자 회원ID (FK)',
    issuer_name     VARCHAR(50)  NOT NULL                COMMENT '문서에 서명되는 의사 성명 스냅샷',
    issuer_license  VARCHAR(50)  NULL                    COMMENT '문서에 서명되는 의사 면허번호 스냅샷',

    issued_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발급시각',

    content_json    JSON         NOT NULL                COMMENT '발급 시점 문서 필드 전체 스냅샷 (재발급 시 이 값을 그대로 출력)',

    ai_draft        TEXT         NULL                    COMMENT 'LLM이 생성한 초안 원문 (의사 수정 전)',
    ai_model        VARCHAR(100) NULL                    COMMENT 'LLM 모델 식별자',
    ai_edited       TINYINT(1)   NULL DEFAULT 0          COMMENT '의사가 초안을 수정했으면 1',

    status          VARCHAR(20)  NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED / VOID',
    void_reason     VARCHAR(300) NULL                    COMMENT '무효 사유',
    voided_at       DATETIME     NULL                    COMMENT '무효 처리 시각',

    reissue_of      BIGINT       NULL                    COMMENT '재발급 원본 증명서ID (FK, self)',

    PRIMARY KEY (certificate_id),
    UNIQUE KEY uk_certificate_serial (serial_no),
    CONSTRAINT fk_cert_visit   FOREIGN KEY (visit_id)   REFERENCES visit(visit_id),
    CONSTRAINT fk_cert_patient FOREIGN KEY (patient_id) REFERENCES patient(patient_id),
    CONSTRAINT fk_cert_member  FOREIGN KEY (issued_by)  REFERENCES member(member_id),
    CONSTRAINT fk_cert_reissue FOREIGN KEY (reissue_of) REFERENCES certificate(certificate_id),
    INDEX idx_cert_patient (patient_id, issued_at),
    INDEX idx_cert_visit (visit_id)
) ENGINE=InnoDB COMMENT='제증명 발급대장 (발급 시점 문서 스냅샷 보존)';
