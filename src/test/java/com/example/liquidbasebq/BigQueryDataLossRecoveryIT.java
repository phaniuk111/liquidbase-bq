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
        "spring.profiles.active=ci-dataloss",
        "spring.autoconfigure.exclude=" // Overrides test/resources/application.properties
})
@ActiveProfiles("ci-dataloss")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Runs explicitly in CI via maven-failsafe plugin filtering.")
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
        jdbcTemplate.execute("DROP TABLE IF EXISTS legacy_users_delete");
        jdbcTemplate.execute("DROP TABLE IF EXISTS legacy_users_trunc");
        jdbcTemplate.execute("DROP SNAPSHOT TABLE IF EXISTS legacy_users_delete_snapshot");
        jdbcTemplate.execute("DROP SNAPSHOT TABLE IF EXISTS legacy_users_trunc_snapshot");

        // CLEANUP: Ensure Liquibase forgets we ran these tests so it re-executes them
        // every time
        try {
            jdbcTemplate.execute(
                    "DELETE FROM DATABASECHANGELOG WHERE ID IN ('destroy-delete-table', 'destroy-truncate-table')");
        } catch (Exception e) {
            System.out.println("Note: DATABASECHANGELOG may not exist yet, skipping cleanup.");
        }

        // Create the tables directly via JDBC (bypassing Liquibase completely)
        jdbcTemplate.execute("CREATE TABLE legacy_users_delete (id INT64, name STRING)");
        jdbcTemplate.execute("CREATE TABLE legacy_users_trunc (id INT64, name STRING)");

        // Insert exactly 10 rows into both tables manually
        for (int i = 1; i <= 10; i++) {
            jdbcTemplate
                    .execute("INSERT INTO legacy_users_delete (id, name) VALUES (" + i + ", 'Legacy User " + i + "')");
            jdbcTemplate
                    .execute("INSERT INTO legacy_users_trunc (id, name) VALUES (" + i + ", 'Legacy User " + i + "')");
        }

        // Verify the data was inserted successfully
        Integer countDelete = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_delete", Integer.class);
        Integer countTrunc = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_trunc", Integer.class);
        assertEquals(10, countDelete);
        assertEquals(10, countTrunc);
    }

    @Test
    @Order(2)
    @DisplayName("Should explicitly generate manual snapshots of both tables before destruction")
    void testExplicitSnapshots() {
        assertDoesNotThrow(() -> {
            jdbcTemplate.execute("CREATE SNAPSHOT TABLE legacy_users_delete_snapshot CLONE legacy_users_delete");
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
    void testExecuteDestructiveChangelog() {
        System.out.println("Starting destructive Liquibase update (no sleep required for snapshots)...");

        // Apply our `dataloss-changelog.xml` which contains the DELETE and TRUNCATE
        assertDoesNotThrow(() -> {
            schemaService.updateSchema();
        });

        // 1. Verify DELETE table has exactly ZERO rows
        Integer countDelete = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_delete", Integer.class);
        assertEquals(0, countDelete);

        // 2. Verify TRUNCATE table exists, but has exactly ZERO rows
        Integer countTruncate = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_trunc", Integer.class);
        assertEquals(0, countTruncate);
    }

    @Test
    @Order(5)
    @DisplayName("Should perfectly restore BOTH tables and ALL 10 original rows via Snapshots")
    void testRollbackAndRestore() {
        // Execute the Snapshot restoration SQL defined in the
        // `<rollback>` blocks
        assertDoesNotThrow(() -> {
            schemaService.rollbackToTag("pre-destruction");
        });

        // Both tables should exist without throwing an error
        System.out.println("Verifying recovery state...");
        Integer restoredDeleteCount = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_delete",
                Integer.class);
        Integer restoredTruncCount = jdbcTemplate.queryForObject("SELECT count(*) FROM legacy_users_trunc",
                Integer.class);

        System.out.println("Recovered Delete Count: " + restoredDeleteCount);
        System.out.println("Recovered Trunc Count: " + restoredTruncCount);

        // BOTH tables must contain exactly 10 rows, definitively proving our Data Loss
        // Recovery strategy works!
        assertEquals(10, restoredDeleteCount, "Snapshot rollback failed to restore deleted data!");
        assertEquals(10, restoredTruncCount, "Snapshot rollback failed to restore truncated data!");
    }
}
