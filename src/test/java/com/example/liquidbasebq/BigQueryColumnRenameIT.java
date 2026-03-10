package com.example.liquidbasebq;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import com.example.liquidbasebq.service.BigQuerySchemaService;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to specifically verify the RENAME COLUMN operation.
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "spring.profiles.active=ci-integration",
        "spring.autoconfigure.exclude=" // Overrides test/resources/application.properties
})
@ActiveProfiles("ci-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CI", matches = "true")
class BigQueryColumnRenameIT {

    @Autowired
    private BigQuerySchemaService schemaService;

    @Autowired
    private DataSource dataSource;

    @Test
    @Order(1)
    @DisplayName("Should successfully rename a column and then rollback the rename")
    void testRenameAndRollback() throws Exception {
        // 1. Apply all changes (including the new rename migration)
        schemaService.updateSchema();

        // 2. Verify 'is_active' exists and 'active_flag' is gone
        assertTrue(columnExists("customers", "is_active"), "Column 'is_active' should exist after update");
        assertFalse(columnExists("customers", "active_flag"), "Column 'active_flag' should be gone after update");

        // 3. Rollback the rename (last 1 changeset)
        schemaService.rollbackByCount(1);

        // 4. Verify 'active_flag' is back and 'is_active' is gone
        assertTrue(columnExists("customers", "active_flag"), "Column 'active_flag' should be back after rollback");
        assertFalse(columnExists("customers", "is_active"), "Column 'is_active' should be gone after rollback");

        // 5. Final update to leave database in clean state
        schemaService.updateSchema();
        assertTrue(columnExists("customers", "is_active"), "Final state: 'is_active' should exist");
    }

    private boolean columnExists(String tableName, String columnName) throws Exception {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            // Query INFORMATION_SCHEMA for the column
            String sql = String.format(
                    "SELECT count(*) FROM `INFORMATION_SCHEMA.COLUMNS` WHERE table_name = '%s' AND column_name = '%s'",
                    tableName, columnName);

            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }
}
