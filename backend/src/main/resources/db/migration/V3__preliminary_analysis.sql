-- ---------------------------------------------------------------------
-- 11. 대기실 키오스크 예비분석 (preliminary_analysis)
--     Visit과 1:1 — 태블릿 키오스크에서 대기 중 촬영한 사진의 AI 예비분석 결과.
--     Visit 상태(FSM)와는 분리된 사이드 채널이며, 정식 analysis_result와는 별개다.
-- ---------------------------------------------------------------------
CREATE TABLE preliminary_analysis (
    preliminary_analysis_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '예비분석 PK',
    visit_id                BIGINT       NOT NULL                COMMENT '접수ID (FK, 1:1)',
    top_k_json              JSON         NULL                    COMMENT 'Top-K 후보 [{code, confidence}, ...]',
    gradcam_url             VARCHAR(500) NULL                    COMMENT 'GradCAM 히트맵 오버레이 이미지 스토리지 키',
    ai_comment              TEXT         NULL                    COMMENT 'LLM 생성 참고 소견',
    source                  VARCHAR(20)  NOT NULL DEFAULT 'clinic' COMMENT '분석에 사용된 모델 소스',
    analyzed_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (preliminary_analysis_id),
    UNIQUE KEY uk_preliminary_visit (visit_id),
    CONSTRAINT fk_preliminary_visit FOREIGN KEY (visit_id) REFERENCES visit(visit_id)
) ENGINE=InnoDB COMMENT='대기실 키오스크 예비분석 결과 (Visit 1:1, FSM과 분리된 사이드 채널)';
