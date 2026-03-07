package com.example.liquidbasebq.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BigQueryBackupServiceTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private Environment environment;

    private BigQueryBackupService service;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[] { "dev1" });
        service = new BigQueryBackupService(dataSource, environment);

        // Inject values
        var projectIdField = BigQueryBackupService.class.getDeclaredField("projectId");
        projectIdField.setAccessible(true);
        projectIdField.set(service, "test-project");

        var datasetIdField = BigQueryBackupService.class.getDeclaredField("datasetId");
        datasetIdField.setAccessible(true);
        datasetIdField.set(service, "test_dataset");

        var gcsBucketField = BigQueryBackupService.class.getDeclaredField("gcsBucket");
        gcsBucketField.setAccessible(true);
        gcsBucketField.set(service, "gs://test-bucket");
    }

    @Nested
    @DisplayName("Configuration Validation")
    class ValidationTests {

        @Test
        @DisplayName("should fail if project ID is missing")
        void shouldFailIfProjectIdMissing() throws Exception {
            var projectIdField = BigQueryBackupService.class.getDeclaredField("projectId");
            projectIdField.setAccessible(true);
            projectIdField.set(service, null);

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.validateConfig());
            assertTrue(e.getMessage().contains("BQ_PROJECT_ID is not configured"));
        }

        @Test
        @DisplayName("should fail if dataset ID is missing")
        void shouldFailIfDatasetIdMissing() throws Exception {
            var datasetIdField = BigQueryBackupService.class.getDeclaredField("datasetId");
            datasetIdField.setAccessible(true);
            datasetIdField.set(service, "");

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.validateConfig());
            assertTrue(e.getMessage().contains("BQ_DATASET_ID is not configured"));
        }
    }

    @Nested
    @DisplayName("Active Profile Lookup")
    class ActiveProfileTests {

        @Test
        @DisplayName("should throw error if multiple profiles active")
        void shouldThrowIfMultipleProfiles() throws Exception {
            when(environment.getActiveProfiles()).thenReturn(new String[] { "dev1", "prd" });

            // Trigger a method that calls getActiveProfile()
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.exportToGcs("PARQUET", "tag"));
            assertTrue(e.getMessage().contains("Exactly one active Spring profile is required"));
        }
    }

    @Nested
    @DisplayName("Time Travel SQL")
    class TimeTravelTests {

        @Test
        @DisplayName("should sanitize table name and generate SQL")
        void shouldGenerateTimeTravelSql() {
            String sql = service.generateTimeTravelSql("malicious; DROP TABLE users;", "2023-10-24 10:00:00");
            assertTrue(sql.contains("malicious__DROP_TABLE_users_"));
            assertFalse(sql.contains("malicious; DROP"));
        }
    }

    @Nested
    @DisplayName("GCS Export")
    class GcsExportTests {

        @Test
        @DisplayName("should export tables to GCS")
        void shouldExportToGcs() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getString("table_name")).thenReturn("users");

            assertDoesNotThrow(() -> service.exportToGcs("PARQUET", "v1"));

            // Verify that execute was called for the EXPORT DATA statement
            verify(statement, atLeastOnce()).execute(anyString());
        }
    }

    @Nested
    @DisplayName("Snapshots")
    class SnapshotTests {

        @Test
        @DisplayName("should generate snapshot suffix via autoBackup")
        void shouldAutoBackup() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getString("table_name")).thenReturn("users");

            String suffix = service.autoBackup();
            assertNotNull(suffix);
            assertTrue(suffix.startsWith("dev1_"));
        }
    }
}
