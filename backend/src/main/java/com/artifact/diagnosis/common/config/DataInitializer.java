package com.artifact.diagnosis.common.config;

import com.artifact.diagnosis.disease.KcdDisease;
import com.artifact.diagnosis.disease.KcdDiseaseRepository;
import com.artifact.diagnosis.drug.DrugMaster;
import com.artifact.diagnosis.drug.DrugMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final int BATCH_SIZE = 500;

    private final KcdDiseaseRepository kcdDiseaseRepository;
    private final DrugMasterRepository drugMasterRepository;

    @Override
    public void run(String... args) {
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

    /** KCD 상병코드 — 1.8MB, XSSFWorkbook으로 충분 */
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
                if (row.getRowNum() == 0) continue;
                String code   = cellToString(row, 0);
                String nameKr = cellToString(row, 1);
                String nameEn = cellToString(row, 2);
                if (code == null || nameKr == null) continue;
                batch.add(KcdDisease.builder().code(code).nameKr(nameKr).nameEn(nameEn).build());
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

    /** 약품 코드 — 14MB 대용량, SAX 스트리밍으로 메모리 최소화 */
    private void loadDrugMaster() {
        InputStream is = getClass().getResourceAsStream("/data/drug_master.xlsx");
        if (is == null) {
            log.warn("drug_master.xlsx 파일 없음 — resources/data/ 에 파일을 넣으면 자동 적재됩니다.");
            return;
        }
        log.info("처방(약품) 코드 적재 시작...");

        List<DrugMaster> batch = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try (OPCPackage pkg = OPCPackage.open(is)) {
            XSSFReader xssfReader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable sst = new ReadOnlySharedStringsTable(pkg);

            int[] rowCount = {0};

            SheetContentsHandler handler = new SheetContentsHandler() {
                private final String[] row = new String[3];

                @Override
                public void startRow(int rowNum) {
                    row[0] = null; row[1] = null; row[2] = null;
                }

                @Override
                public void endRow(int rowNum) {
                    rowCount[0]++;
                    if (rowNum == 0) return;
                    String code   = row[0];
                    String nameKr = row[1];
                    String nameEn = row[2];
                    if (code == null || nameKr == null) return;
                    if (!seen.add(code)) return;
                    batch.add(DrugMaster.builder().code(code).nameKr(nameKr).nameEn(nameEn).build());
                    if (batch.size() >= BATCH_SIZE) {
                        drugMasterRepository.saveAll(batch);
                        batch.clear();
                    }
                }

                @Override
                public void cell(String cellRef, String value, XSSFComment comment) {
                    if (cellRef == null || value == null) return;
                    int col = CellReference.convertColStringToIndex(cellRef.replaceAll("[0-9]", ""));
                    if (col >= 0 && col < 3) row[col] = value.trim().isEmpty() ? null : value.trim();
                }

                @Override
                public void headerFooter(String text, boolean isHeader, String tagName) {}
            };

            XSSFSheetXMLHandler xmlHandler = new XSSFSheetXMLHandler(
                    xssfReader.getStylesTable(), null, sst, handler,
                    new org.apache.poi.ss.usermodel.DataFormatter(), false);

            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser saxParser = factory.newSAXParser();
            XMLReader sheetParser = saxParser.getXMLReader();
            sheetParser.setContentHandler(xmlHandler);

            Iterator<InputStream> sheets = xssfReader.getSheetsData();
            boolean hasSheet = sheets.hasNext();
            log.info("약품 엑셀 시트 존재: {}", hasSheet);
            if (hasSheet) {
                sheetParser.parse(new InputSource(sheets.next()));
            }
            log.info("약품 SAX 파싱 완료 — 총 {}행 읽음, batch 잔여 {}건", rowCount[0], batch.size());

            if (!batch.isEmpty()) drugMasterRepository.saveAll(batch);

        } catch (Exception e) {
            log.error("처방(약품) 코드 적재 실패: {}", e.getMessage(), e);
            return;
        }
        log.info("처방(약품) 코드 적재 완료: {}건", drugMasterRepository.count());
    }

    private String cellToString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        String value = switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> null;
        };
        return (value == null || value.isBlank()) ? null : value;
    }
}
