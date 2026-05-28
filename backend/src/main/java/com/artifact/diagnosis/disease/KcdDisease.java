package com.artifact.diagnosis.disease;

import jakarta.persistence.*;
import lombok.*;

/**
 * KCD 상병코드 마스터. 엑셀 import로 수만 건 적재.
 * 동일 코드(예: A000)에 여러 이름이 있을 수 있어 code는 UNIQUE 아님.
 * DB 테이블: kcd_disease
 */
@Entity
@Table(name = "kcd_disease",
        indexes = {
            @Index(name = "idx_kcd_code",    columnList = "code"),
            @Index(name = "idx_kcd_name_kr", columnList = "name_kr")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KcdDisease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "kcd_id")
    private Long id;

    /** KCD 코드 (A00, A000, H1008 등) */
    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "name_kr", nullable = false, length = 300)
    private String nameKr;

    @Column(name = "name_en", length = 500)
    private String nameEn;
}
