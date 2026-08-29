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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

/**
 * 기동 직후 한 번 도는 데이터 초기화 — 테이블을 "채우는" 일만 한다.
 *
 * 테이블을 "만드는" 일은 여기서 하지 않는다. 스키마는 Flyway(db/migration)가 책임진다.
 * 예전에는 이 클래스가 CREATE TABLE / ALTER TABLE 로 빠진 테이블과 컬럼을 런타임에
 * 보정했다. MySQL 의 docker-entrypoint-initdb.d 가 데이터 볼륨이 비어 있을 때만 돌아서,
 * 이미 데이터가 있는 DB 에는 나중에 추가된 스키마가 영영 안 들어갔기 때문이다.
 *
 * 그 보정 코드는 Flyway 도입과 함께 지웠다. 되살리지 않는 편이 좋은 이유가 두 가지 있다.
 *   - 진실의 출처가 둘로 갈린다. 같은 테이블 정의가 V*.sql 과 자바 문자열에 각각 있으면
 *     한쪽만 고쳤을 때 어느 쪽이 맞는지 아무도 모르게 된다.
 *   - 애초에 실행되지 않는다. CommandLineRunner 는 애플리케이션 컨텍스트가 다 뜬 뒤에
 *     돌고, ddl-auto=validate 는 그보다 먼저 EntityManagerFactory 를 만들 때 돈다.
 *     스키마가 어긋나 있으면 여기 도달하기 전에 기동이 실패한다.
 *
 * 스키마를 바꿔야 하면 새 번호의 마이그레이션(V6, V7…)을 추가한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final int BATCH_SIZE = 500;

    private final KcdDiseaseRepository kcdDiseaseRepository;
    private final DrugMasterRepository drugMasterRepository;
    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 초기 관리자 계정. 미설정(빈 값)이면 계정을 만들지 않는다. */
    @Value("${admin.bootstrap.login-id:}")
    private String adminLoginId;

    @Value("${admin.bootstrap.password:}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        // 실패하면 예외가 그대로 올라가 기동이 멈춘다(예전에는 스키마 보정 코드의 try 안에 있어
        // log.warn 으로 묻혔다). member 테이블은 Flyway 가 보장하므로 여기서 터진다는 것은
        // DB 자체에 문제가 있다는 뜻이고, 그런 상태로는 뜨지 않는 편이 낫다.
        bootstrapAdminAccount();

        // 마스터 데이터(KCD 5만건 + 약품 50만건)는 적재에 수십 초가 걸린다.
        // 메인 스레드에서 하면 그동안 서버가 요청을 못 받아 헬스체크가 실패한다.
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

    /**
     * 초기 ADMIN 계정 생성.
     *
     * ADMIN_LOGIN_ID / ADMIN_PASSWORD 가 모두 설정된 경우에만 동작하며,
     * 비밀번호 해시는 기동 시점에 계산한다 — 소스코드에 계정 정보를 남기지 않기 위함이다.
     * 이미 같은 login_id 가 있으면 아무것도 하지 않는다(재기동 시 덮어쓰기 방지).
     */
    private void bootstrapAdminAccount() {
        if (adminLoginId.isBlank() || adminPassword.isBlank()) {
            log.info("초기 관리자 계정: ADMIN_LOGIN_ID/ADMIN_PASSWORD 미설정 → 생성하지 않음");
            return;
        }

        // created_at/updated_at 을 SQL 이 직접 채운다. 이 INSERT 는 JdbcTemplate 원시 SQL 이라
        // Member 엔티티의 @CreationTimestamp 를 거치지 않고, DB 기본값(DEFAULT CURRENT_TIMESTAMP)은
        // MySQL 마이그레이션에만 있다 — H2 로 도는 테스트에서는 NOT NULL 위반으로 실패한다.
        int inserted = jdbcTemplate.update("""
                INSERT INTO member (login_id, password, name, department, role, created_at, updated_at)
                SELECT ?, ?, ?, ?, 'ADMIN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                WHERE NOT EXISTS (SELECT 1 FROM member WHERE login_id = ?)
                """,
                adminLoginId,
                passwordEncoder.encode(adminPassword),
                "관리자",
                "피부과",
                adminLoginId);

        if (inserted > 0) {
            log.info("초기 관리자 계정을 생성했습니다: {}", adminLoginId);
        } else {
            log.info("초기 관리자 계정: 이미 존재함({}), 스킵", adminLoginId);
        }
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
