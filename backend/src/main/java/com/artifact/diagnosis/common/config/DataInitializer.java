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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.sql.DataSource;
import java.sql.Connection;
import java.io.InputStream;
import java.sql.ResultSet;
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
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        ensureMemberTable();
        ensurePreliminaryAnalysisTable();
        ensureVisitReceptionMemoColumn();
        ensureVisitKioskTokenColumn();
        ensureAnalysisResultHeatmapColumn();

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

    private void ensureMemberTable() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "member")) {
                jdbcTemplate.execute("""
                        CREATE TABLE member (
                            member_id      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '회원ID (PK)',
                            login_id       VARCHAR(50)  NOT NULL                COMMENT '로그인 ID',
                            password       VARCHAR(100) NOT NULL                COMMENT '비밀번호 (BCrypt)',
                            name           VARCHAR(50)  NOT NULL                COMMENT '이름',
                            license_number VARCHAR(50)  NULL                    COMMENT '면허번호 (의사/간호사)',
                            department     VARCHAR(100) NULL                    COMMENT '진료과',
                            role           VARCHAR(20)  NOT NULL DEFAULT 'DOCTOR' COMMENT '역할 (DOCTOR/NURSE/STAFF/ADMIN)',
                            created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            PRIMARY KEY (member_id),
                            UNIQUE KEY uk_member_login_id (login_id),
                            UNIQUE KEY uk_member_license_number (license_number)
                        ) ENGINE=InnoDB COMMENT='회원 계정 (의사/간호사/일반)'
                        """);
                log.info("member 테이블을 생성했습니다.");
            } else {
                ensureMemberColumn(connection, "license_number",
                        "ALTER TABLE member ADD COLUMN license_number VARCHAR(50) NULL");
                ensureMemberColumn(connection, "department",
                        "ALTER TABLE member ADD COLUMN department VARCHAR(100) NULL");
                ensureMemberColumn(connection, "role",
                        "ALTER TABLE member ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'DOCTOR'");
                ensureMemberColumn(connection, "created_at",
                        "ALTER TABLE member ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
                ensureMemberColumn(connection, "updated_at",
                        "ALTER TABLE member ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");

                if (!indexExists(connection, "member", "uk_member_login_id")) {
                    jdbcTemplate.execute("ALTER TABLE member ADD UNIQUE KEY uk_member_login_id (login_id)");
                    log.info("member.login_id unique index를 추가했습니다.");
                }

                if (!indexExists(connection, "member", "uk_member_license_number")) {
                    jdbcTemplate.execute("ALTER TABLE member ADD UNIQUE KEY uk_member_license_number (license_number)");
                    log.info("member.license_number unique index를 추가했습니다.");
                }
            }

            jdbcTemplate.update("""
                    INSERT INTO member (login_id, password, name, license_number, department, role)
                    SELECT ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM member WHERE login_id = ?)
                    """,
                    "admin",
                    "$2b$10$4/MYOFj/eAOxU64eE0sOpO0hujwKyfmEETSQwLgY8a3.pRc1czsrW",
                    "관리자",
                    "TEST-0001",
                    "피부과",
                    "ADMIN",
                    "admin");
        } catch (Exception e) {
            log.warn("member 테이블 확인/보정 중 오류: {}", e.getMessage());
        }
    }

    private void ensureMemberColumn(Connection connection, String columnName, String alterSql) {
        try {
            if (columnExists(connection, "member", columnName)) {
                return;
            }

            jdbcTemplate.execute(alterSql);
            log.info("member.{} 컬럼을 추가했습니다.", columnName);
        } catch (Exception e) {
            log.warn("member.{} 컬럼 확인/추가 중 오류: {}", columnName, e.getMessage());
        }
    }

    private void ensurePreliminaryAnalysisTable() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "preliminary_analysis")) {
                jdbcTemplate.execute("""
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
                        ) ENGINE=InnoDB COMMENT='대기실 키오스크 예비분석 결과 (Visit 1:1, FSM과 분리된 사이드 채널)'
                        """);
                log.info("preliminary_analysis 테이블을 생성했습니다.");
            } else {
                ensurePreliminaryAnalysisColumn(connection, "top_k_json",
                        "ALTER TABLE preliminary_analysis ADD COLUMN top_k_json JSON NULL");
                ensurePreliminaryAnalysisColumn(connection, "gradcam_url",
                        "ALTER TABLE preliminary_analysis ADD COLUMN gradcam_url VARCHAR(500) NULL");
                ensurePreliminaryAnalysisColumn(connection, "ai_comment",
                        "ALTER TABLE preliminary_analysis ADD COLUMN ai_comment TEXT NULL");
                ensurePreliminaryAnalysisColumn(connection, "source",
                        "ALTER TABLE preliminary_analysis ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'clinic'");
                ensurePreliminaryAnalysisColumn(connection, "analyzed_at",
                        "ALTER TABLE preliminary_analysis ADD COLUMN analyzed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");

                if (!indexExists(connection, "preliminary_analysis", "uk_preliminary_visit")) {
                    jdbcTemplate.execute("ALTER TABLE preliminary_analysis ADD UNIQUE KEY uk_preliminary_visit (visit_id)");
                    log.info("preliminary_analysis.visit_id unique index를 추가했습니다.");
                }
            }
        } catch (Exception e) {
            log.warn("preliminary_analysis 테이블 확인/보정 중 오류: {}", e.getMessage());
        }
    }

    private void ensurePreliminaryAnalysisColumn(Connection connection, String columnName, String alterSql) {
        try {
            if (columnExists(connection, "preliminary_analysis", columnName)) {
                return;
            }

            jdbcTemplate.execute(alterSql);
            log.info("preliminary_analysis.{} 컬럼을 추가했습니다.", columnName);
        } catch (Exception e) {
            log.warn("preliminary_analysis.{} 컬럼 확인/추가 중 오류: {}", columnName, e.getMessage());
        }
    }

    private void ensureVisitReceptionMemoColumn() {
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(
                     connection.getCatalog(), null, "visit", "reception_memo")) {
            if (columns.next()) {
                return;
            }

            jdbcTemplate.execute("ALTER TABLE visit ADD COLUMN reception_memo TEXT NULL");
            log.info("visit.reception_memo 컬럼을 추가했습니다.");
        } catch (Exception e) {
            log.warn("visit.reception_memo 컬럼 확인/추가 중 오류: {}", e.getMessage());
        }
    }

    /** 키오스크 QR 진입용 토큰 컬럼. 기존 볼륨에서도 재기동만으로 추가되도록 런타임에 보정한다. */
    private void ensureVisitKioskTokenColumn() {
        try (Connection connection = dataSource.getConnection()) {
            if (!columnExists(connection, "visit", "kiosk_token")) {
                jdbcTemplate.execute("ALTER TABLE visit ADD COLUMN kiosk_token CHAR(12) NULL");
                log.info("visit.kiosk_token 컬럼을 추가했습니다.");
            }

            if (!indexExists(connection, "visit", "uk_visit_kiosk_token")) {
                jdbcTemplate.execute("ALTER TABLE visit ADD UNIQUE KEY uk_visit_kiosk_token (kiosk_token)");
                log.info("visit.kiosk_token unique index를 추가했습니다.");
            }
        } catch (Exception e) {
            log.warn("visit.kiosk_token 컬럼 확인/추가 중 오류: {}", e.getMessage());
        }
    }

    private void ensureAnalysisResultHeatmapColumn() {
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(
                     connection.getCatalog(), null, "analysis_result", "heatmap_image_url")) {
            if (columns.next()) {
                return;
            }

            jdbcTemplate.execute("ALTER TABLE analysis_result ADD COLUMN heatmap_image_url VARCHAR(500) NULL");
            log.info("analysis_result.heatmap_image_url 컬럼을 추가했습니다.");
        } catch (Exception e) {
            log.warn("analysis_result.heatmap_image_url 컬럼 확인/추가 중 오류: {}", e.getMessage());
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, tableName, columnName)) {
            return columns.next();
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws Exception {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, tableName, false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
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
