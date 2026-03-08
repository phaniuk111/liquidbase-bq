package com.example.liquidbasebq.runner;

import com.example.liquidbasebq.service.BigQueryBackupService;
import com.example.liquidbasebq.service.BigQuerySchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SchemaManagerRunner}.
 * Verifies that each CLI action correctly delegates to the schema/backup
 * service.
 */
@ExtendWith(MockitoExtension.class)
class SchemaManagerRunnerTest {

    @Mock
    private BigQuerySchemaService schemaService;

    @Mock
    private BigQueryBackupService backupService;

    @Mock
    private com.example.liquidbasebq.validation.ValidationConfig validationConfig;

    @Mock
    private Environment environment;

    private SchemaManagerRunner runner;

    @BeforeEach
    void setUp() {
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[] { "dev1" });
        runner = new SchemaManagerRunner(schemaService, backupService, validationConfig, environment);
    }

    @Test
    @DisplayName("default action should call getChangeSetStatus()")
    void defaultActionShouldCallStatus() throws Exception {
        runner.run();
        verify(schemaService).getChangeSetStatus();
    }

    @Test
    @DisplayName("--action=status should call getChangeSetStatus()")
    void statusAction() throws Exception {
        runner.run("--action=status");
        verify(schemaService).getChangeSetStatus();
    }

    @Test
    @DisplayName("--action=update should call updateSchema()")
    void updateAction() throws Exception {
        runner.run("--action=update");
        verify(schemaService).updateSchema();
    }

    @Test
    @DisplayName("--action=update-sql should call generateUpdateSql()")
    void updateSqlAction() throws Exception {
        when(schemaService.generateUpdateSql()).thenReturn("CREATE TABLE test;");
        runner.run("--action=update-sql");
        verify(schemaService).generateUpdateSql();
    }

    @Test
    @DisplayName("--action=validate-strict should call validateStrict()")
    void validateStrictAction() throws Exception {
        runner.run("--action=validate-strict");
        verify(validationConfig).validateStrict();
    }

    @Test
    @DisplayName("--action=validate should call validateChangelog()")
    void validateAction() throws Exception {
        runner.run("--action=validate");
        verify(schemaService).validateChangelog();
    }

    @Test
    @DisplayName("--action=history should call getHistory()")
    void historyAction() throws Exception {
        runner.run("--action=history");
        verify(schemaService).getHistory();
    }

    @Test
    @DisplayName("--action=rollback with --tag should call rollbackToTag()")
    void rollbackWithTagAction() throws Exception {
        runner.run("--action=rollback", "--tag=baseline-v1");
        verify(schemaService).rollbackToTag("baseline-v1");
    }

    @Test
    @DisplayName("--action=rollback without --tag should throw IllegalArgumentException")
    void rollbackWithoutTagShouldThrow() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=rollback"));
        verify(schemaService, never()).rollbackToTag(anyString());
    }

    @Test
    @DisplayName("--action=rollback-count with --count should call rollbackByCount()")
    void rollbackCountAction() throws Exception {
        runner.run("--action=rollback-count", "--count=5");
        verify(schemaService).rollbackByCount(5);
    }

    @Test
    @DisplayName("--action=rollback-count defaults to 1 when --count not specified")
    void rollbackCountDefaultsToOne() throws Exception {
        runner.run("--action=rollback-count");
        verify(schemaService).rollbackByCount(1);
    }

    @Test
    @DisplayName("--count with invalid format should throw IllegalArgumentException")
    void rollbackCountInvalidFormatShouldThrow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> runner.run("--action=rollback-count", "--count=abc"));
        assertTrue(e.getMessage().contains("Invalid value for --count"));
    }

    @Test
    @DisplayName("--count with zero or negative should throw IllegalArgumentException")
    void rollbackCountNegativeShouldThrow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> runner.run("--action=rollback-count", "--count=-1"));
        assertTrue(e.getMessage().contains("must be greater than 0"));
    }

    @Test
    @DisplayName("--action=rollback-sql with --tag should call generateRollbackSql()")
    void rollbackSqlAction() throws Exception {
        when(schemaService.generateRollbackSql("baseline-v1")).thenReturn("DROP TABLE test;");
        runner.run("--action=rollback-sql", "--tag=baseline-v1");
        verify(schemaService).generateRollbackSql("baseline-v1");
    }

    @Test
    @DisplayName("--action=rollback-sql without --tag should throw IllegalArgumentException")
    void rollbackSqlWithoutTagShouldThrow() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=rollback-sql"));
        verify(schemaService, never()).generateRollbackSql(anyString());
    }

    @Test
    @DisplayName("--action=tag with --tag should call tagDatabase()")
    void tagAction() throws Exception {
        runner.run("--action=tag", "--tag=release-1.0");
        verify(schemaService).tagDatabase("release-1.0");
    }

    @Test
    @DisplayName("--action=tag without --tag should throw IllegalArgumentException")
    void tagWithoutTagShouldThrow() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=tag"));
        verify(schemaService, never()).tagDatabase(anyString());
    }

    @Test
    @DisplayName("--action=clear-checksums should call clearCheckSums()")
    void clearChecksumsAction() throws Exception {
        runner.run("--action=clear-checksums");
        verify(schemaService).clearCheckSums();
    }

    // ──────────── BACKUP & RESTORE TESTS ────────────

    @Test
    @DisplayName("--action=backup with --tag should call createTableSnapshots()")
    void backupWithTagAction() throws Exception {
        runner.run("--action=backup", "--tag=pre_release");
        verify(backupService).createTableSnapshots("pre_release");
    }

    @Test
    @DisplayName("--action=backup without --tag should call autoBackup()")
    void backupWithoutTagAction() throws Exception {
        when(backupService.autoBackup()).thenReturn("dev1_20260306_120000");
        runner.run("--action=backup");
        verify(backupService).autoBackup();
    }

    @Test
    @DisplayName("--action=backup-copy with --tag should call createDatasetCopy()")
    void backupCopyAction() throws Exception {
        runner.run("--action=backup-copy", "--tag=full_backup");
        verify(backupService).createDatasetCopy("full_backup");
    }

    @Test
    @DisplayName("--action=restore with --tag should call restoreFromSnapshots()")
    void restoreAction() throws Exception {
        runner.run("--action=restore", "--tag=dev1_20260306_120000");
        verify(backupService).restoreFromSnapshots("dev1_20260306_120000");
    }

    @Test
    @DisplayName("--action=time-travel-sql should call generateTimeTravelSql()")
    void timeTravelSqlAction() throws Exception {
        when(backupService.generateTimeTravelSql("orders", "2026-03-06 04:00:00 UTC"))
                .thenReturn("SELECT * FROM orders FOR SYSTEM_TIME...");
        runner.run("--action=time-travel-sql", "--tag=orders", "--timestamp=2026-03-06 04:00:00 UTC");
        verify(backupService).generateTimeTravelSql("orders", "2026-03-06 04:00:00 UTC");
    }

    @ParameterizedTest
    @ValueSource(strings = { "unknown", "deploy", "migrate", "INVALID" })
    @DisplayName("unknown actions should throw IllegalArgumentException")
    void unknownActionShouldThrow(String action) {
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=" + action));
        verifyNoInteractions(schemaService);
    }

    @Test
    @DisplayName("should parse multiple arguments correctly")
    void shouldParseMultipleArgs() throws Exception {
        runner.run("--action=rollback", "--tag=v2.0", "--count=3");
        verify(schemaService).rollbackToTag("v2.0");
    }

    @Test
    @DisplayName("--action=restore-table with --tag and --table should call restoreTableFromSnapshot()")
    void restoreTableAction() throws Exception {
        runner.run("--action=restore-table", "--tag=dev1_20260306_120000", "--table=payment_methods");
        verify(backupService).restoreTableFromSnapshot("dev1_20260306_120000", "payment_methods");
    }

    @Test
    @DisplayName("--action=restore-table missing arguments throws exception")
    void restoreTableMissingArgsShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=restore-table"));
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=restore-table", "--tag=v1"));
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=restore-table", "--table=tbl"));
    }

    @Test
    @DisplayName("--action=time-travel-sql without tag or timestamp throws exception")
    void timeTravelMissingArgsShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=time-travel-sql"));
    }

    @Test
    @DisplayName("--action=export-gcs calls exportToGcs()")
    void exportGcsAction() throws Exception {
        runner.run("--action=export-gcs", "--tag=v1", "--format=JSON");
        verify(backupService).exportToGcs("v1", "JSON");
    }

    @Test
    @DisplayName("--action=export-gcs without tag throws exception")
    void exportGcsMissingTagThrows() {
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=export-gcs"));
    }

    @Test
    @DisplayName("--action=restore-gcs-script calls generateGcsRestoreScript()")
    void restoreGcsScriptAction() throws Exception {
        runner.run("--action=restore-gcs-script", "--tag=gs://bucket/path", "--format=CSV");
        verify(backupService).generateGcsRestoreScript("gs://bucket/path", "CSV");
    }

    @Test
    @DisplayName("--action=restore-gcs-script missing tag throws exception")
    void restoreGcsScriptMissingTagThrows() {
        assertThrows(IllegalArgumentException.class, () -> runner.run("--action=restore-gcs-script"));
    }

    @Test
    @DisplayName("getActiveProfile throws error if multiple profiles active")
    void multipleProfilesThrowsException() {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "dev1", "prd" });
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> runner.run("--action=status"));
        assertTrue(e.getMessage().contains("Exactly one active Spring profile is required"));
    }
}
