package com.artifact.diagnosis.common.config;

import com.artifact.diagnosis.disease.KcdDisease;
import com.artifact.diagnosis.disease.KcdDiseaseRepository;
import com.artifact.diagnosis.drug.DrugMaster;
import com.artifact.diagnosis.drug.DrugMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 앱 시작 시 KCD 상병코드 / 처방(약품) 코드를 엑셀에서 DB로 적재.
 * 이미 데이터가 있으면 스킵 — 재시작 시 중복 insert 없음.
 *
 * 엑셀 파일 위치:
 *   backend/src/main/resources/data/kcd_disease.xlsx  (컬럼: 상병코드 | 상병명 | 상병명(영문))
 *   backend/src/main/resources/data/drug_master.xlsx  (컬럼: 처방코드 | 처방명 | 처방명 영문)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final int BATCH_SIZE = 500;

    private final KcdDiseaseRepository kcdDiseaseRepository;
    private final DrugMasterRepository drugMasterRepository;

    @Override
    public void run(String... args) {
        // 대용량 엑셀 적재를 백그라운드 스레드로 분리 — 앱 시작을 블로킹하지 않음
        new Thread(() -> {
            try {
                if (kcdDiseaseRepository.count() == 0) loadKcdDiseases();
                else log.info("KCD 상병코드: 이미 적재됨 ({}건), 스킵", kcdDiseaseRepository.count());

                if (drugMasterRepository.count() == 0) loadDrugMaster();
                else log.info("처방(약품) 코드: 이미 적재됨 ({}건), 스킵", drugMasterRepository.count());
            } catch (Exception e) {
                log.error("데이터 초기화 중 오류 발생", e);
            }
        }, "data-initializer").start();
    }

    private void loadKcdDiseases() {
        InputStream is = getClass().getResourceAsStream("/data/kcd_disease.xlsx");
        if (is == null) {
            log.warn("kcd_disease.xlsx 파일 없음 — resources/data/ 에 파일을 넣으면 자동 적재됩니다.");
            return;
        }
        log.info("KCD 상병코드 적재 시작...");
        try (Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            List<KcdDisease> batch = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; // 헤더 스킵
                String code   = cellToString(row, 0);
                String nameKr = cellToString(row, 1);
                String nameEn = cellToString(row, 2);
                if (code == null || nameKr == null) continue;
                batch.add(KcdDisease.builder()
                        .code(code).nameKr(nameKr).nameEn(nameEn).build());
                if (batch.size() >= BATCH_SIZE) {
                    kcdDiseaseRepository.saveAll(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) kcdDiseaseRepository.saveAll(batch);
        } catch (Exception e) {
            log.error("KCD 상병코드 적재 실패: {}", e.getMessage());
            return;
        }
        log.info("KCD 상병코드 적재 완료: {}건", kcdDiseaseRepository.count());
    }

    private void loadDrugMaster() {
        InputStream is = getClass().getResourceAsStream("/data/drug_master.xlsx");
        if (is == null) {
            log.warn("drug_master.xlsx 파일 없음 — resources/data/ 에 파일을 넣으면 자동 적재됩니다.");
            return;
        }
        log.info("처방(약품) 코드 적재 시작...");
        try (Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            List<DrugMaster> batch = new ArrayList<>();
            Set<String> seen = new HashSet<>(); // 엑셀 내 중복 코드 방지
            int skipped = 0;
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                String code   = cellToString(row, 0);
                String nameKr = cellToString(row, 1);
                String nameEn = cellToString(row, 2);
                if (code == null || nameKr == null) continue;
                if (!seen.add(code)) { skipped++; continue; } // 중복 코드 스킵
                batch.add(DrugMaster.builder()
                        .code(code).nameKr(nameKr).nameEn(nameEn).build());
                if (batch.size() >= BATCH_SIZE) {
                    drugMasterRepository.saveAll(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) drugMasterRepository.saveAll(batch);
            if (skipped > 0) log.info("처방코드 중복 스킵: {}건", skipped);
        } catch (Exception e) {
            log.error("처방(약품) 코드 적재 실패: {}", e.getMessage());
            return;
        }
        log.info("처방(약품) 코드 적재 완료: {}건", drugMasterRepository.count());
    }

    /** 셀 값을 문자열로 변환. 숫자 셀(처방코드 등)도 처리. */
    private String cellToString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        String value = switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                // 소수점 없이 정수로 변환 (050000011.0 → 050000011)
                long num = (long) cell.getNumericCellValue();
                yield String.valueOf(num);
            }
            default -> null;
        };
        return (value == null || value.isBlank()) ? null : value;
    }
}
