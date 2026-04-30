-- =====================================================================
-- Team Artifact - 의료 영상 기반 AI 보조 진단/처방 지원 시스템
-- MySQL 8.0+ Schema v0.3
-- =====================================================================
-- 변경 이력:
--   v0.1: 팀장 초안 (4테이블)
--   v0.2: AI 분석 결과 / 처방 상세 / 처방 템플릿 추가, 데이터 타입 명시
--   v0.3: prescription_detail에 prescription_type ENUM 추가
-- =====================================================================

CREATE DATABASE IF NOT EXISTS artifact_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE artifact_db;

-- ---------------------------------------------------------------------
-- 1. 환자정보 (patient)
-- ---------------------------------------------------------------------
CREATE TABLE patient (
    patient_id      BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '환자번호 (PK)',
    name            VARCHAR(50)     NOT NULL                 COMMENT '성명',
    birth_date      DATE            NULL                     COMMENT '생년월일',
    gender          ENUM('M', 'F', 'OTHER') NULL             COMMENT '성별',
    phone           VARCHAR(20)     NULL                     COMMENT '연락처',
    memo            TEXT            NULL                     COMMENT '의료진 메모',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (patient_id),
    INDEX idx_patient_name (name)
) ENGINE=InnoDB COMMENT='환자 마스터';

-- ---------------------------------------------------------------------
-- 2. 병명 (disease) — HAM10000 7-class 미리 적재
-- ---------------------------------------------------------------------
CREATE TABLE disease (
    disease_id      BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '질병ID (PK)',
    disease_code    VARCHAR(20)     NOT NULL UNIQUE          COMMENT 'HAM10000 코드',
    name_ko         VARCHAR(100)    NOT NULL                 COMMENT '한글 병명',
    name_en         VARCHAR(100)    NULL                     COMMENT '영문 병명',
    description     TEXT            NULL                     COMMENT '질병 설명',
    severity        ENUM('LOW', 'MEDIUM', 'HIGH') NULL       COMMENT '심각도',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (disease_id)
) ENGINE=InnoDB COMMENT='질병 마스터 (HAM10000 기준)';

INSERT INTO disease (disease_code, name_ko, name_en, severity) VALUES
  ('nv',    '멜라닌세포모반',         'Melanocytic nevus',                          'LOW'),
  ('mel',   '악성 흑색종',            'Melanoma',                                   'HIGH'),
  ('bkl',   '양성 각화증성 병변',     'Benign keratosis-like lesions',              'LOW'),
  ('bcc',   '기저세포암',             'Basal cell carcinoma',                       'HIGH'),
  ('akiec', '광선각화증/상피내암',    'Actinic keratoses / Intraepithelial carcinoma','MEDIUM'),
  ('df',    '피부섬유종',             'Dermatofibroma',                             'LOW'),
  ('vasc',  '혈관성 병변',            'Vascular lesions',                           'LOW');

-- ---------------------------------------------------------------------
-- 3. 내원 (visit)
-- ---------------------------------------------------------------------
CREATE TABLE visit (
    visit_id            BIGINT      NOT NULL AUTO_INCREMENT  COMMENT '접수ID (PK)',
    patient_id          BIGINT      NOT NULL                 COMMENT '환자번호 (FK)',
    visit_date          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '접수일자',
    status              ENUM('RECEIVED','IMAGE_UPLOADED','ANALYZING','ANALYZED',
                             'DIAGNOSED','PRESCRIBED','COMPLETED','CANCELLED') 
                        NOT NULL DEFAULT 'RECEIVED'          COMMENT '진행상태',
    image_url           VARCHAR(500) NULL                    COMMENT '이미지 URL (오브젝트 스토리지)',
    image_uploaded_at   DATETIME    NULL                     COMMENT '이미지 업로드 시각',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (visit_id),
    CONSTRAINT fk_visit_patient FOREIGN KEY (patient_id) REFERENCES patient(patient_id),
    INDEX idx_visit_status (status),
    INDEX idx_visit_date (visit_date)
) ENGINE=InnoDB COMMENT='내원/접수';

-- ---------------------------------------------------------------------
-- 4. AI 분석 결과 (analysis_result) ★ 별도 테이블 ★
-- ---------------------------------------------------------------------
CREATE TABLE analysis_result (
    analysis_id          BIGINT     NOT NULL AUTO_INCREMENT  COMMENT '분석ID (PK)',
    visit_id             BIGINT     NOT NULL                 COMMENT '접수ID (FK)',
    model_version        VARCHAR(50) NOT NULL                COMMENT '모델 버전 (예: efficientnet-b2-v1)',
    predicted_disease_id BIGINT     NOT NULL                 COMMENT 'Top-1 예측 질병ID (FK)',
    confidence           DECIMAL(5,4) NOT NULL               COMMENT 'Top-1 신뢰도 (0.0000~1.0000)',
    top_k_results        JSON       NULL                     COMMENT 'Top-3 후보 [{code, conf}, ...]',
    inference_time_ms    INT        NULL                     COMMENT '추론 소요 시간(ms)',
    analyzed_at          DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (analysis_id),
    CONSTRAINT fk_analysis_visit   FOREIGN KEY (visit_id)             REFERENCES visit(visit_id),
    CONSTRAINT fk_analysis_disease FOREIGN KEY (predicted_disease_id) REFERENCES disease(disease_id),
    INDEX idx_analysis_visit (visit_id)
) ENGINE=InnoDB COMMENT='AI 모델 분석 결과 (visit과 1:N — 같은 이미지 재분석 가능)';

-- ---------------------------------------------------------------------
-- 5. 처방템플릿 (prescription_template) — 질병별 권장 처방
-- ---------------------------------------------------------------------
CREATE TABLE prescription_template (
    template_id     BIGINT          NOT NULL AUTO_INCREMENT,
    disease_id      BIGINT          NOT NULL                 COMMENT '질병ID (FK)',
    prescription_type ENUM('MEDICATION','TOPICAL','INJECTION','PROCEDURE','OBSERVATION','REFERRAL')
                    NOT NULL                                 COMMENT '처방 타입',
    medicine_name   VARCHAR(200)    NOT NULL                 COMMENT '권장 약품/시술명',
    dosage          VARCHAR(100)    NULL                     COMMENT '용법',
    duration_days   INT             NULL                     COMMENT '복용/적용 기간(일)',
    notes           TEXT            NULL                     COMMENT '주의사항',
    PRIMARY KEY (template_id),
    CONSTRAINT fk_template_disease FOREIGN KEY (disease_id) REFERENCES disease(disease_id),
    INDEX idx_template_disease (disease_id)
) ENGINE=InnoDB COMMENT='질병별 기본 처방 템플릿 (DB 매핑 룰)';

-- ---------------------------------------------------------------------
-- 6. 처방 (prescription) — 헤더
-- ---------------------------------------------------------------------
CREATE TABLE prescription (
    prescription_id          BIGINT  NOT NULL AUTO_INCREMENT COMMENT '처방ID (PK)',
    visit_id                 BIGINT  NOT NULL                COMMENT '접수ID (FK)',
    disease_id               BIGINT  NOT NULL                COMMENT '의사 확정 질병ID (FK)',
    analysis_id              BIGINT  NULL                    COMMENT '근거 AI 분석 (FK, nullable)',
    prescribed_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '처방시각',
    revisit_recommended_date DATE    NULL                    COMMENT '재내원 권장 날짜',
    doctor_notes             TEXT    NULL                    COMMENT '의사 소견',
    created_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (prescription_id),
    CONSTRAINT fk_prescription_visit    FOREIGN KEY (visit_id)    REFERENCES visit(visit_id),
    CONSTRAINT fk_prescription_disease  FOREIGN KEY (disease_id)  REFERENCES disease(disease_id),
    CONSTRAINT fk_prescription_analysis FOREIGN KEY (analysis_id) REFERENCES analysis_result(analysis_id),
    INDEX idx_prescription_visit (visit_id)
) ENGINE=InnoDB COMMENT='최종 처방 헤더';

-- ---------------------------------------------------------------------
-- 7. 처방상세 (prescription_detail) ★ "처방 방법"이 들어가는 테이블 ★
-- ---------------------------------------------------------------------
CREATE TABLE prescription_detail (
    detail_id         BIGINT          NOT NULL AUTO_INCREMENT,
    prescription_id   BIGINT          NOT NULL                 COMMENT '처방ID (FK)',
    prescription_type ENUM('MEDICATION','TOPICAL','INJECTION','PROCEDURE','OBSERVATION','REFERRAL')
                      NOT NULL                                 COMMENT '처방 타입',
    medicine_name     VARCHAR(200)    NOT NULL                 COMMENT '약품/시술명',
    dosage            VARCHAR(100)    NULL                     COMMENT '용법',
    duration_days     INT             NULL                     COMMENT '복용/적용 기간(일)',
    notes             TEXT            NULL                     COMMENT '주의사항',
    PRIMARY KEY (detail_id),
    CONSTRAINT fk_detail_prescription FOREIGN KEY (prescription_id) REFERENCES prescription(prescription_id)
        ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='처방 상세 항목들 (한 처방에 여러 줄)';
