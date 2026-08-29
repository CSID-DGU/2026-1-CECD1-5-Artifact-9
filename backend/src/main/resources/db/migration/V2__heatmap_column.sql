ALTER TABLE analysis_result
    ADD COLUMN heatmap_image_url VARCHAR(500) NULL
        COMMENT 'GradCAM 히트맵 오버레이 이미지 S3 키';
