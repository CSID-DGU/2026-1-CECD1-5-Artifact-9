package com.artifact.diagnosis.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class MySqlSchemaMigrator implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        String database = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        if (database == null || !database.toLowerCase().contains("mysql")) {
            return;
        }

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS doctor (
                    doctor_id BIGINT NOT NULL AUTO_INCREMENT,
                    login_id VARCHAR(50) NOT NULL,
                    password VARCHAR(100) NOT NULL,
                    name VARCHAR(50) NOT NULL,
                    license_number VARCHAR(50) NULL,
                    department VARCHAR(100) NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (doctor_id),
                    UNIQUE KEY uk_doctor_login_id (login_id),
                    UNIQUE KEY uk_doctor_license_number (license_number)
                ) ENGINE=InnoDB
                """);

        jdbcTemplate.update("""
                INSERT INTO doctor (login_id, password, name, license_number, department)
                SELECT 'admin', '1234', '관리 의사', 'TEST-0001', '피부과'
                WHERE NOT EXISTS (SELECT 1 FROM doctor WHERE login_id = 'admin')
                """);

        addColumnIfMissing("prescription", "doctor_id",
                "ALTER TABLE prescription ADD COLUMN doctor_id BIGINT NULL COMMENT '작성 의사ID'");
        addColumnIfMissing("prescription", "doctor_name",
                "ALTER TABLE prescription ADD COLUMN doctor_name VARCHAR(50) NULL COMMENT '처방 당시 의사명 스냅샷'");

        jdbcTemplate.update("""
                UPDATE prescription p
                JOIN doctor d ON d.login_id = 'admin'
                SET p.doctor_id = COALESCE(p.doctor_id, d.doctor_id),
                    p.doctor_name = COALESCE(p.doctor_name, d.name)
                WHERE p.doctor_id IS NULL OR p.doctor_name IS NULL
                """);

        makeColumnNotNullIfNullable("prescription", "doctor_id", "ALTER TABLE prescription MODIFY doctor_id BIGINT NOT NULL");
        makeColumnNotNullIfNullable("prescription", "doctor_name", "ALTER TABLE prescription MODIFY doctor_name VARCHAR(50) NOT NULL");
        addIndexIfMissing("prescription", "idx_presc_doctor_date",
                "ALTER TABLE prescription ADD INDEX idx_presc_doctor_date (doctor_id, prescribed_at)");
        addForeignKeyIfMissing("prescription", "fk_presc_doctor",
                "ALTER TABLE prescription ADD CONSTRAINT fk_presc_doctor FOREIGN KEY (doctor_id) REFERENCES doctor(doctor_id)");
    }

    private void addColumnIfMissing(String tableName, String columnName, String sql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count != null && count == 0) {
            jdbcTemplate.execute(sql);
            log.info("MySQL schema migrated: added {}.{}", tableName, columnName);
        }
    }

    private void makeColumnNotNullIfNullable(String tableName, String columnName, String sql) {
        String nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, String.class, tableName, columnName);
        if ("YES".equalsIgnoreCase(nullable)) {
            jdbcTemplate.execute(sql);
        }
    }

    private void addIndexIfMissing(String tableName, String indexName, String sql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, Integer.class, tableName, indexName);
        if (count != null && count == 0) {
            jdbcTemplate.execute(sql);
        }
    }

    private void addForeignKeyIfMissing(String tableName, String constraintName, String sql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = ?
                """, Integer.class, tableName, constraintName);
        if (count != null && count == 0) {
            jdbcTemplate.execute(sql);
        }
    }
}
