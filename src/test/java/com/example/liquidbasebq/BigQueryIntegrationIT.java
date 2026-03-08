package com.example.liquidbasebq;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import com.example.liquidbasebq.service.BigQueryBackupService;
import com.example.liquidbasebq.service.BigQuerySchemaService;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ⚠️ WARNING: This test connects to a REAL Google BigQuery project and incurs
 * costs.
 * It is intended to run *only* in the GitHub Actions CI pipeline against a
 * temporary sandbox dataset via the 'ci-integration' profile.
 * 
 * To run locally, you must be authenticated manually and set:
 * export BQ_PROJECT_ID=your-project
 * export BQ_DATASET_ID=integration_sandbox
 * ./mvnw verify -Pci-integration
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "spring.profiles.active=ci-integration"
})
@ActiveProfiles("ci-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Runs explicitly in CI via maven-failsafe plugin filtering or when manually invoked.")
class BigQueryIntegrationIT {

    @Autowired
    private BigQuerySchemaService schemaService;

    @Autowired
    private BigQueryBackupService backupService;

    @Test
    @Order(1)
    @DisplayName("Should successfully apply the entire master changelog (Update)")
    void testFullSchemaUpdate() {
        // We verify that no exception is thrown when applying our DDLs to a blank
        // dataset
        assertDoesNotThrow(() -> {
            schemaService.updateSchema();
        });
    }

    @Test
    @Order(2)
    @DisplayName("Should successfully rollback to the initialization baseline")
    void testRollbackToTag() {
        // Assumes a tag 'baseline-v1' exists. If your baseline has a different tag,
        // update this.
        assertDoesNotThrow(() -> {
            schemaService.rollbackToTag("baseline-v1");
        });
    }

    @Test
    @Order(3)
    @DisplayName("Should successfully show database status")
    void testShowStatus() {
        assertDoesNotThrow(() -> {
            schemaService.getChangeSetStatus();
        });
    }

    @Test
    @Order(4)
    @DisplayName("Should successfully show deployment history")
    void testShowHistory() {
        assertDoesNotThrow(() -> {
            schemaService.getHistory();
        });
    }

    @Test
    @Order(5)
    @DisplayName("Should successfully tag the database")
    void testTagDatabase() {
        assertDoesNotThrow(() -> {
            schemaService.tagDatabase("integration-test-tag");
        });
    }

    @Test
    @Order(6)
    @DisplayName("Should successfully export dataset to GCS")
    void testExportToGcs() {
        assertDoesNotThrow(() -> {
            // Provide dummy but valid strings for the export to ensure the API call
            // formulation works
            backupService.exportToGcs("gs://test-integration-bucket/export", "PARQUET");
        });
    }

    @Test
    @Order(7)
    @DisplayName("Should successfully apply the changesets again after rollback")
    void testReapplyAfterRollback() {
        assertDoesNotThrow(() -> {
            schemaService.updateSchema();
        });
    }
}
