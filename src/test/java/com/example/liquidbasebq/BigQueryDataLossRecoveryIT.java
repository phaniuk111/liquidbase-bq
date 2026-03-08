package com.example.liquidbasebq;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.liquidbasebq.service.BigQuerySchemaService;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "spring.profiles.active=ci-dataloss"
})
@ActiveProfiles("ci-dataloss")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Disabled("Disabled by default locally. Runs explicitly in CI via maven-failsafe plugin filtering.")
class BigQueryDataLossRecoveryIT {

    @Autowired
    private BigQuerySchemaService schemaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    @DisplayName("Should successfully establish legacy tables with fake pre-existing data using manual JDBC")
    void testEstablishLegacyData() {
        // Drop them if they somehow exist from a dirty previous run
        jdbcTemplate.execute("DROP TABLE IF EXISTS legacy_users_drop");
        jdbcTemplate.execute("DROP TABLE IF EXISTS legacy_users_trunc");
        jdbcTemplate.execute("DROP TABLE IF EXISTS legacy_users_trunc_snapshot");

        // Create the tables directly via JDBC (bypassing Liquibase completely)
        jdbcTemplate.execute("CREATE TABLE legacy_users_drop (id INT64, name STRING)");
        jdbcTemplate.execute("CREATE TABLE legacy_users_trunc (id INT64, name STRING)");

        // Insert exactly 10 rows into both tables manually
        for (int i = 1; i <= 10; i++) {
            jdbcTemplate
                    .execute("INSERT INTO legacy_users_drop (id, name) VALUES (" + i + ", 'Legacy User " + i + "')");
            jdbcTemplate
                    .execute("INSERT INTO legacy_users_trunc (id, name) VALUES (" + i + ", 'Legacy User " + i + "')");
        }

        // Verify the data was inserted successfully
        Integer countDrop = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_drop", Integer.class);
        Integer countTrunc = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_trunc", Integer.class);
        assertEquals(10, countDrop);
        assertEquals(10, countTrunc);
    }

    @Test
    @Order(2)
    @DisplayName("Should explicitly generate a manual snapshot of the truncate table before destruction")
    void testExplicitSnapshot() {
        assertDoesNotThrow(() -> {
            jdbcTemplate.execute("CREATE SNAPSHOT TABLE legacy_users_trunc_snapshot CLONE legacy_users_trunc");
        });
    }

    @Test
    @Order(3)
    @DisplayName("Should initialize Liquibase and tag the database perfectly BEFORE the destruction")
    void testPreDestructionTag() {
        // Just setting a rollback target explicitly before the bad changelogs run
        assertDoesNotThrow(() -> {
            schemaService.tagDatabase("pre-destruction");
        });
    }

    @Test
    @Order(4)
    @DisplayName("Should successfully delete and truncate data via Liquibase execution")
    void testExecuteDestructiveChangelog() throws InterruptedException {
        // Pausing extremely briefly to guarantee our TIMESTAMP_SUB clock arithmetic
        // inside
        // the rollback SQL doesn't pull a microsecond from before the table existed!
        Thread.sleep(5000);

        // Apply our `dataloss-changelog.xml` which contains the DROP and TRUNCATE
        assertDoesNotThrow(() -> {
            schemaService.updateSchema();
        });

        // 1. Verify DROP table throws a SQL exception because it's completely gone
        Exception dropException = assertThrows(org.springframework.jdbc.BadSqlGrammarException.class, () -> {
            jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_drop", Integer.class);
        });
        assertTrue(dropException.getMessage().contains("Not found: Table"));

        // 2. Verify TRUNCATE table exists, but has exactly ZERO rows
        Integer countTruncate = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_trunc", Integer.class);
        assertEquals(0, countTruncate);
    }

    @Test
    @Order(5)
    @DisplayName("Should perfectly restore BOTH tables and ALL 10 original rows via Time Travel and Snapshots")
    void testRollbackAndRestore() {
        // Execute the Time Travel and Snapshot restoration SQL defined in the
        // `<rollback>` blocks
        assertDoesNotThrow(() -> {
            schemaService.rollbackToTag("pre-destruction");
        });

        // Both tables should exist without throwing an error
        Integer restoredDropCount = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_drop",
                Integer.class);
        Integer restoredTruncCount = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_trunc",
                Integer.class);

        // BOTH tables must contain exactly 10 rows, definitively proving our Data Loss
        // Recovery strategy works!
        assertEquals(10, restoredDropCount, "Time Travel Rollback failed to restore the dropped 10 rows!");
        assertEquals(10, restoredTruncCount, "Snapshot Rollback failed to restore the truncated 10 rows!");
    }
}
