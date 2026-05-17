package com.artifact.diagnosis.visit;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 내원 1건에 첨부된 이미지 1장. visit_id 로 묶인다.
 * DB 테이블: visit_image
 */
@Entity
@Table(name = "visit_image")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long id;

    @Column(name = "visit_id", nullable = false)
    private Long visitId;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;
}
