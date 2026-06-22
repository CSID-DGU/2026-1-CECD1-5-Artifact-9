-- =====================================================================
-- Team Artifact - 의료 영상 기반 AI 보조 진단/처방 지원 시스템
-- MySQL 8.0+ Schema v0.4
-- =====================================================================
-- 변경 이력:
--   v0.1: 팀장 초안 (4테이블)
--   v0.2: AI 분석 결과 / 처방 상세 / 처방 템플릿 추가
--   v0.3: prescription_detail에 prescription_type ENUM 추가
--   v0.4: visit 상태 IN_PROGRESS 추가, image_url 제거
--         visit_image / analysis_image / kcd_disease / drug_master 추가
--         prescription → kcd_disease_id, prescription_detail → drug_id
--   v0.5: doctor 테이블 및 prescription 작성 의사 스냅샷 추가
-- =====================================================================

CREATE DATABASE IF NOT EXISTS artifact_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE artifact_db;

-- 클라이언트 연결 charset을 utf8mb4로 명시 (한글 이중인코딩 방지)
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0. 회원 계정 (member) — 의사/간호사/일반(접수) 통합
-- ---------------------------------------------------------------------
CREATE TABLE member (
    member_id      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '회원ID (PK)',
    login_id       VARCHAR(50)  NOT NULL                COMMENT '로그인 ID',
    password       VARCHAR(100) NOT NULL                COMMENT '비밀번호 (BCrypt)',
    name           VARCHAR(50)  NOT NULL                COMMENT '이름',
    license_number VARCHAR(50)  NULL                    COMMENT '면허번호 (의사/간호사)',
    department     VARCHAR(100) NULL                    COMMENT '진료과',
    role           VARCHAR(20)  NOT NULL DEFAULT 'DOCTOR' COMMENT '역할 (DOCTOR/NURSE/STAFF/ADMIN)',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (member_id),
    UNIQUE KEY uk_member_login_id (login_id),
    UNIQUE KEY uk_member_license_number (license_number)
) ENGINE=InnoDB COMMENT='회원 계정 (의사/간호사/일반)';

INSERT INTO member (login_id, password, name, license_number, department, role) VALUES
  ('admin', '$2b$10$4/MYOFj/eAOxU64eE0sOpO0hujwKyfmEETSQwLgY8a3.pRc1czsrW', '관리자', 'TEST-0001', '피부과', 'ADMIN');
-- 위 해시는 '1234'의 BCrypt 암호화값

-- ---------------------------------------------------------------------
-- 1. 환자정보 (patient)
-- ---------------------------------------------------------------------
CREATE TABLE patient (
    patient_id  BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '환자번호 (PK)',
    name        VARCHAR(50)     NOT NULL                 COMMENT '성명',
    birth_date  DATE            NULL                     COMMENT '생년월일',
    gender      ENUM('M','F','OTHER') NULL               COMMENT '성별',
    phone       VARCHAR(20)     NULL                     COMMENT '연락처',
    memo        TEXT            NULL                     COMMENT '의료진 메모',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (patient_id),
    INDEX idx_patient_name (name)
) ENGINE=InnoDB COMMENT='환자 마스터';

-- ---------------------------------------------------------------------
-- 2. 병명 마스터 (disease) — HAM10000 7-class (AI 모델 출력 기준)
-- ---------------------------------------------------------------------
CREATE TABLE disease (
    disease_id   BIGINT      NOT NULL AUTO_INCREMENT  COMMENT '질병ID (PK)',
    disease_code VARCHAR(20) NOT NULL UNIQUE          COMMENT 'HAM10000 코드 (nv, mel, ...)',
    name_ko      VARCHAR(100) NOT NULL                COMMENT '한글 병명',
    name_en      VARCHAR(100) NULL                    COMMENT '영문 병명',
    description  TEXT        NULL                     COMMENT '질병 설명',
    severity     ENUM('LOW','MEDIUM','HIGH') NULL     COMMENT '심각도',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (disease_id)
) ENGINE=InnoDB COMMENT='AI 모델 출력 병명 마스터 (HAM10000 7종)';

INSERT INTO disease (disease_code, name_ko, name_en, severity) VALUES
  ('nv',    '멜라닌세포모반',      'Melanocytic nevus',                           'LOW'),
  ('mel',   '악성 흑색종',         'Melanoma',                                    'HIGH'),
  ('bkl',   '양성 각화증성 병변',  'Benign keratosis-like lesions',               'LOW'),
  ('bcc',   '기저세포암',          'Basal cell carcinoma',                        'HIGH'),
  ('akiec', '광선각화증/상피내암', 'Actinic keratoses / Intraepithelial carcinoma','MEDIUM'),
  ('df',    '피부섬유종',           'Dermatofibroma',                              'LOW'),
  ('vasc',  '혈관성 병변',          'Vascular lesions',                            'LOW');

-- ---------------------------------------------------------------------
-- 3. KCD 상병코드 마스터 (kcd_disease) — 엑셀 import, 수만 건
--    동일 코드에 여러 이름이 존재할 수 있어 code에 UNIQUE 없음
-- ---------------------------------------------------------------------
CREATE TABLE kcd_disease (
    kcd_id  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '상병 PK',
    code    VARCHAR(10)  NOT NULL                COMMENT 'KCD 코드 (A00, A000, H1008)',
    name_kr VARCHAR(300) NOT NULL                COMMENT '상병명(한글)',
    name_en VARCHAR(500) NULL                    COMMENT '상병명(영문)',
    PRIMARY KEY (kcd_id),
    INDEX idx_kcd_code    (code),
    INDEX idx_kcd_name_kr (name_kr(100))
) ENGINE=InnoDB COMMENT='KCD 상병코드 마스터 (의사 진단명 선택용)';

-- ---------------------------------------------------------------------
-- 4. 처방(약품) 코드 마스터 (drug_master) — 엑셀 import, 수만 건
-- ---------------------------------------------------------------------
CREATE TABLE drug_master (
    drug_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '약품 PK',
    code    VARCHAR(15)  NOT NULL UNIQUE         COMMENT '처방코드 (050000011)',
    name_kr VARCHAR(300) NOT NULL                COMMENT '처방명(한글)',
    name_en VARCHAR(300) NULL                    COMMENT '처방명(영문)',
    PRIMARY KEY (drug_id),
    INDEX idx_drug_code    (code),
    INDEX idx_drug_name_kr (name_kr(100))
) ENGINE=InnoDB COMMENT='처방(약품) 코드 마스터 (의사 약품 선택용)';

-- ---------------------------------------------------------------------
-- 5. 내원 (visit)
-- ---------------------------------------------------------------------
CREATE TABLE visit (
    visit_id   BIGINT   NOT NULL AUTO_INCREMENT COMMENT '접수ID (PK)',
    patient_id BIGINT   NOT NULL                COMMENT '환자번호 (FK)',
    visit_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '접수일자',
    status     ENUM('RECEIVED','IN_PROGRESS','ANALYZING','ANALYZED',
                    'DIAGNOSED','PRESCRIBED','COMPLETED','CANCELLED')
               NOT NULL DEFAULT 'RECEIVED'      COMMENT '진행상태',
    reception_memo TEXT NULL COMMENT '진료 메모',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (visit_id),
    CONSTRAINT fk_visit_patient FOREIGN KEY (patient_id) REFERENCES patient(patient_id),
    INDEX idx_visit_status (status),
    INDEX idx_visit_date   (visit_date)
) ENGINE=InnoDB COMMENT='내원/접수';

-- ---------------------------------------------------------------------
-- 6. 내원 이미지 (visit_image) — 접수 1건에 이미지 여러 장
-- ---------------------------------------------------------------------
CREATE TABLE visit_image (
    image_id    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '이미지 PK',
    visit_id    BIGINT       NOT NULL                COMMENT '접수ID (FK)',
    image_url   VARCHAR(500) NOT NULL                COMMENT 'S3 객체 키',
    uploaded_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '업로드 시각',
    PRIMARY KEY (image_id),
    CONSTRAINT fk_vi_visit FOREIGN KEY (visit_id) REFERENCES visit(visit_id),
    INDEX idx_vi_visit (visit_id)
) ENGINE=InnoDB COMMENT='내원별 업로드 이미지';

-- ---------------------------------------------------------------------
-- 7. AI 분석 결과 (analysis_result)
-- ---------------------------------------------------------------------
CREATE TABLE analysis_result (
    analysis_id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '분석ID (PK)',
    visit_id             BIGINT       NOT NULL                COMMENT '접수ID (FK)',
    model_version        VARCHAR(50)  NOT NULL                COMMENT '모델 버전',
    predicted_disease_id BIGINT       NOT NULL                COMMENT 'Top-1 질병ID (FK→disease)',
    confidence           DECIMAL(5,4) NOT NULL                COMMENT 'Top-1 신뢰도',
    top_k_results        JSON         NULL                    COMMENT 'Top-5 [{disease_code, confidence}, ...]',
    inference_time_ms    INT          NULL                    COMMENT '추론 소요(ms)',
    analyzed_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (analysis_id),
    CONSTRAINT fk_analysis_visit   FOREIGN KEY (visit_id)             REFERENCES visit(visit_id),
    CONSTRAINT fk_analysis_disease FOREIGN KEY (predicted_disease_id) REFERENCES disease(disease_id),
    INDEX idx_analysis_visit (visit_id)
) ENGINE=InnoDB COMMENT='AI 모델 분석 결과';

-- ---------------------------------------------------------------------
-- 8. 분석-이미지 매핑 (analysis_image) — 어떤 이미지로 분석했는지 N:M
-- ---------------------------------------------------------------------
CREATE TABLE analysis_image (
    analysis_id BIGINT NOT NULL COMMENT '분석ID (FK)',
    image_id    BIGINT NOT NULL COMMENT '이미지ID (FK)',
    PRIMARY KEY (analysis_id, image_id),
    CONSTRAINT fk_ai_analysis FOREIGN KEY (analysis_id) REFERENCES analysis_result(analysis_id),
    CONSTRAINT fk_ai_image    FOREIGN KEY (image_id)    REFERENCES visit_image(image_id)
) ENGINE=InnoDB COMMENT='분석에 사용된 이미지 매핑';

-- ---------------------------------------------------------------------
-- 9. 처방 헤더 (prescription)
-- ---------------------------------------------------------------------
CREATE TABLE prescription (
    prescription_id        BIGINT   NOT NULL AUTO_INCREMENT COMMENT '처방ID (PK)',
    visit_id               BIGINT   NOT NULL                COMMENT '접수ID (FK)',
    member_id              BIGINT   NOT NULL                COMMENT '처방 작성 회원ID (FK)',
    member_name            VARCHAR(50) NOT NULL             COMMENT '처방 당시 작성자명 스냅샷',
    analysis_id            BIGINT   NULL                    COMMENT '근거 AI 분석ID (FK, nullable)',
    prescribed_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '처방시각',
    revisit_recommended_date DATE   NULL                    COMMENT '재내원 권장일',
    doctor_notes           TEXT     NULL                    COMMENT '의사 소견',
    created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (prescription_id),
    CONSTRAINT fk_presc_visit    FOREIGN KEY (visit_id)       REFERENCES visit(visit_id),
    CONSTRAINT fk_presc_member   FOREIGN KEY (member_id)      REFERENCES member(member_id),
    CONSTRAINT fk_presc_analysis FOREIGN KEY (analysis_id)    REFERENCES analysis_result(analysis_id),
    INDEX idx_presc_visit (visit_id),
    INDEX idx_presc_member_date (member_id, prescribed_at)
) ENGINE=InnoDB COMMENT='최종 처방 헤더';

CREATE TABLE prescription_disease (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    prescription_id BIGINT      NOT NULL,
    kcd_disease_id  BIGINT      NOT NULL,
    is_primary      TINYINT(1)  NOT NULL DEFAULT 0  COMMENT '1=주상병 0=부상병',
    PRIMARY KEY (id),
    CONSTRAINT fk_pd_presc FOREIGN KEY (prescription_id) REFERENCES prescription(prescription_id) ON DELETE CASCADE,
    CONSTRAINT fk_pd_kcd   FOREIGN KEY (kcd_disease_id)  REFERENCES kcd_disease(kcd_id)
) ENGINE=InnoDB COMMENT='처방별 상병 (주/부상병)';

-- ---------------------------------------------------------------------
-- 10. 처방 상세 (prescription_detail)
-- ---------------------------------------------------------------------
CREATE TABLE prescription_detail (
    detail_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '상세 PK',
    prescription_id   BIGINT       NOT NULL                COMMENT '처방ID (FK)',
    drug_id           BIGINT       NULL                    COMMENT '약품 마스터ID (FK, 직접입력 시 NULL)',
    medicine_name     VARCHAR(300) NOT NULL                COMMENT '약품명 (drug_master에서 복사 또는 직접입력)',
    dosage            VARCHAR(100) NULL                    COMMENT '용법',
    duration_days     INT          NULL                    COMMENT '복용 기간(일)',
    notes             TEXT         NULL                    COMMENT '주의사항',
    PRIMARY KEY (detail_id),
    CONSTRAINT fk_detail_presc FOREIGN KEY (prescription_id) REFERENCES prescription(prescription_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_detail_drug  FOREIGN KEY (drug_id) REFERENCES drug_master(drug_id)
) ENGINE=InnoDB COMMENT='처방 상세 항목';

