package com.artifact.diagnosis.analysis;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * 분석 1건이 실제로 모델에 넣은 이미지 매핑.
 * DB 테이블: analysis_image
 *
 * 왜 이 테이블이 필요한가. {@link AnalysisResult} 에는 이미지 컬럼이 하나도 없다 —
 * {@code visit_id} 뿐이다. 그런데 {@code visit_image} 는 접수 1건에 사진이 여러 장 붙는 구조라,
 * 이 매핑이 없으면 "이 진단이 어느 사진에서 나왔는가"에 DB가 답할 수 없다.
 * 모델 버전은 가중치 해시로 정확히 남는데 정작 입력이 무엇이었는지가 비면 감사 추적이 반쪽이 된다.
 *
 * 왜 복합 PK (analysis_id, image_id) 인가. 처음부터 N:M 을 상정했기 때문이다.
 *   - 한 분석이 여러 장을 함께 본다 — 같은 병변의 여러 각도. 다중 입력 모델로 확장할 때 쓴다.
 *   - 한 장이 여러 분석에 쓰인다 — 모델을 교체한 뒤 같은 사진을 재분석하는 경우.
 *       {@code AnalysisResult} 의 "같은 이미지를 여러 번 재분석할 수 있음"이 이것이다.
 * 그래서 이 클래스는 지금 당장 1행만 쌓이더라도 리스트를 받는 경로로 저장된다.
 * 확장 시 {@link AnalysisService} 의 {@code selectInferenceTargets} 한 곳만 고치면 된다.
 *
 * 2026-08-30 기록. 이 테이블은 2026-05-17(e31e890) 스키마 v0.4 에서
 * {@code visit.image_url}(1장) → {@code visit_image}(N장) 전환과 함께 만들어졌으나,
 * 채우는 코드가 끝내 들어오지 않아 3개월 넘게 0행으로 비어 있었다.
 * {@code analyze(Long, List<Long>)} 가 리스트를 받으면서 {@code get(0)} 만 쓰던 것이 그 화석이다.
 */
@Entity
@Table(name = "analysis_image")
@IdClass(AnalysisImage.Key.class)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalysisImage {

    @Id
    @Column(name = "analysis_id")
    private Long analysisId;

    @Id
    @Column(name = "image_id")
    private Long imageId;

    /**
     * 복합 PK 식별자.
     *
     * JPA 규약상 {@code @IdClass} 는 {@code Serializable} 이어야 하고 equals/hashCode 가
     * 반드시 있어야 한다 — 없으면 영속성 컨텍스트가 같은 행을 다른 행으로 취급해
     * 중복 INSERT 나 조용한 덮어쓰기가 난다.
     */
    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long analysisId;
        private Long imageId;
    }
}
