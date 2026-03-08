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

        @Test
        @DisplayName("should restore from snapshots successfully")
        void shouldRestoreFromSnapshots() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            // Simulate 2 tables to restore
            when(resultSet.next()).thenReturn(true, true, false);
            when(resultSet.getString("table_name")).thenReturn("users", "orders");

            assertDoesNotThrow(() -> service.restoreFromSnapshots("v1"));
            verify(statement, times(2)).execute(contains("CREATE OR REPLACE TABLE"));
        }

        @Test
        @DisplayName("restoreFromSnapshots fails if snapshot dataset is empty")
        void restoreFromSnapshotsEmpty() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(false);

            RuntimeException e = assertThrows(RuntimeException.class, () -> service.restoreFromSnapshots("v1"));
            assertTrue(e.getMessage().contains("No tables found in snapshot dataset"));
        }

        @Test
        @DisplayName("should restore single table from snapshot")
        void shouldRestoreTableFromSnapshot() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            // Simulate 2 tables in the snapshot
            when(resultSet.next()).thenReturn(true, true, false);
            when(resultSet.getString("table_name")).thenReturn("users", "orders");

            assertDoesNotThrow(() -> service.restoreTableFromSnapshot("v1", "orders"));
            verify(statement, times(1)).execute(contains("CREATE OR REPLACE TABLE `test-project.test_dataset.orders`"));
        }

        @Test
        @DisplayName("restoreTableFromSnapshot fails if table not in snapshot")
        void restoreTableFromSnapshotMissingTable() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getString("table_name")).thenReturn("users");

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.restoreTableFromSnapshot("v1", "orders"));
            assertTrue(e.getMessage().contains("not found in snapshot dataset"));
        }
    }

    @Nested
    @DisplayName("Dataset Copy")
    class DatasetCopyTests {

        @Test
        @DisplayName("should copy dataset successfully")
        void shouldCopyDataset() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            // Simulate 1 table to copy
            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getString("table_name")).thenReturn("users");

            var executedSql = service.createDatasetCopy("v1");
            assertEquals(2, executedSql.size()); // CREATE SCHEMA + CREATE TABLE AS SELECT
            verify(statement, times(2)).execute(anyString());
        }

        @Test
        @DisplayName("copy dataset generates correctly but does nothing if empty")
        void shouldCopyDatasetEmpty() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            // Simulate 0 tables
            when(resultSet.next()).thenReturn(false);

            var executedSql = service.createDatasetCopy("v1");
            assertEquals(1, executedSql.size()); // Just CREATE SCHEMA
            verify(statement, times(1)).execute(contains("CREATE SCHEMA"));
        }
    }

    @Nested
    @DisplayName("GCS Restore Script Generation")
    class GcsRestoreScriptTests {

        @Test
        @DisplayName("should generate bq load script")
        void shouldGenerateRestoreScript() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getString("table_name")).thenReturn("users");

            String script = service.generateGcsRestoreScript("gs://bucket/path", "PARQUET");
            assertTrue(script.contains("#!/bin/bash"));
            assertTrue(script.contains("bq load --source_format=PARQUET"));
            assertTrue(script.contains("test-project.test_dataset.users"));
            assertTrue(script.contains("gs://bucket/path/users/*"));
        }
    }
}
