package com.artifact.diagnosis.drug;

import jakarta.persistence.*;
import lombok.*;

/**
 * 처방(약품) 코드 마스터. 엑셀 import로 수만 건 적재.
 * DB 테이블: drug_master
 */
@Entity
@Table(name = "drug_master",
        indexes = {
            @Index(name = "idx_drug_code",    columnList = "code"),
            @Index(name = "idx_drug_name_kr", columnList = "name_kr")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DrugMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "drug_id")
    private Long id;

    /** 처방코드 (050000011 등) — 고유 */
    @Column(name = "code", nullable = false, unique = true, length = 15)
    private String code;

    @Column(name = "name_kr", nullable = false, length = 300)
    private String nameKr;

    @Column(name = "name_en", length = 300)
    private String nameEn;
}
