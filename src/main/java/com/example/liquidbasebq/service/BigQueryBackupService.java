package com.example.liquidbasebq.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import jakarta.annotation.PostConstruct;

/**
 * BigQuery backup service — multiple strategies for data protection.
 * <p>
 * Strategies (ordered by retention):
 * <ol>
 * <li><b>Time Travel</b> — Built-in, up to 7 days, zero cost. No action
 * needed.</li>
 * <li><b>Table Snapshots</b> — Configurable expiration (default 30 days), zero
 * incremental cost.</li>
 * <li><b>Dataset Copy</b> — Permanent, full storage cost. Good for
 * pre-migration backups.</li>
 * <li><b>GCS Export</b> — Permanent, cheapest long-term storage
 * (Parquet/Avro/CSV/JSON).
 * Best for compliance and archival (&gt;7 days).</li>
 * </ol>
 */
@Service
public class BigQueryBackupService {

    private static final Logger log = LoggerFactory.getLogger(BigQueryBackupService.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(java.time.ZoneOffset.UTC);

    private final DataSource dataSource;
    private final Environment environment;

    @Value("${BQ_PROJECT_ID:}")
    private String projectId;

    @Value("${BQ_DATASET_ID:}")
    private String datasetId;

    /** GCS bucket for long-term exports. Set via env var or property. */
    @Value("${BQ_BACKUP_GCS_BUCKET:}")
    private String gcsBucket;

    /** Snapshot expiration in days. Default = 30 (max BigQuery allows is 90). */
    @Value("${BQ_SNAPSHOT_EXPIRY_DAYS:30}")
    private int snapshotExpiryDays;

    public BigQueryBackupService(DataSource dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @PostConstruct
    public void validateConfig() {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("BQ_PROJECT_ID is not configured");
        }
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalStateException("BQ_DATASET_ID is not configured");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // STRATEGY 1: Table Snapshots (up to 90 days)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Create snapshots of all tables in the dataset.
     * Uses configurable expiration via {@code BQ_SNAPSHOT_EXPIRY_DAYS} (default:
     * 30).
     *
     * @param snapshotSuffix suffix for snapshot dataset
     * @return list of SQL statements executed
     */
    public List<String> createTableSnapshots(String snapshotSuffix) throws Exception {
        String safeSuffix = snapshotSuffix.replaceAll("[^a-zA-Z0-9_]", "_");
        String profile = getActiveProfile();
        String snapshotDataset = datasetId + "_snapshots_" + safeSuffix;
        List<String> executedSql = new ArrayList<>();

        log.info("╔═══════════════════════════════════════════════════════════╗");
        log.info("║  Creating Table Snapshots                                ║");
        log.info("╠═══════════════════════════════════════════════════════════╣");
        log.info("║  Profile    : {}", profile);
        log.info("║  Source     : {}.{}", projectId, datasetId);
        log.info("║  Snapshots  : {}.{}", projectId, snapshotDataset);
        log.info("║  Expiry     : {} days", snapshotExpiryDays);
        log.info("╚═══════════════════════════════════════════════════════════╝");

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            // 1. Create snapshot dataset with configurable expiration
            String createDatasetSql = String.format(
                    "CREATE SCHEMA IF NOT EXISTS `%s.%s` OPTIONS(default_table_expiration_days=%d)",
                    projectId, snapshotDataset, snapshotExpiryDays);
            stmt.execute(createDatasetSql);
            executedSql.add(createDatasetSql);
            log.info("Created snapshot dataset: {} (expires in {} days)", snapshotDataset, snapshotExpiryDays);

            // 2. List all tables in the source dataset
            List<String> tables = listTables(stmt);
            if (tables.isEmpty()) {
                log.info("No tables found in dataset {}.{}. Skipping snapshot.", projectId, datasetId);
                return executedSql;
            }
            log.info("Found {} tables to snapshot", tables.size());

            // 3. Create a snapshot of each table
            int failures = 0;
            for (String table : tables) {
                String snapshotSql = String.format(
                        "CREATE SNAPSHOT TABLE `%s.%s.%s` CLONE `%s.%s.%s`",
                        projectId, snapshotDataset, table,
                        projectId, datasetId, table);
                try {
                    stmt.execute(snapshotSql);
                    executedSql.add(snapshotSql);
                    log.info("  ✓ Snapshot: {}", table);
                } catch (Exception e) {
                    log.warn("  ✗ Failed to snapshot {}: {}", table, e.getMessage());
                    failures++;
                }
            }

            if (failures > 0) {
                throw new RuntimeException(
                        "Snapshot backup failed for " + failures + " tables. Aborting to prevent data loss.");
            }

            log.info("Snapshot complete — {} tables backed up to {}", tables.size(), snapshotDataset);
        }

        return executedSql;
    }

    /**
     * Restore multiple tables from their snapshots.
     * <p>
     * <b>WARNING (Non-Atomic Operation):</b> BigQuery does not support multi-table
     * transactions. If a restore operation fails mid-way (e.g., table 5 of 10
     * fails),
     * tables 1-4 will have been restored, but tables 5-10 will not, leaving the
     * dataset in a partial/inconsistent state. For production, consider restoring
     * to
     * a staging dataset first, then swapping.
     * </p>
     *
     * @param snapshotSuffix the suffix used when creating snapshots
     */
    public void restoreFromSnapshots(String snapshotSuffix) throws Exception {
        String safeSuffix = snapshotSuffix.replaceAll("[^a-zA-Z0-9_]", "_");
        String snapshotDataset = datasetId + "_snapshots_" + safeSuffix;

        log.info("Restoring ALL tables from snapshots: {}.{}", projectId, snapshotDataset);
        log.warn("⚠ This will overwrite ALL live tables with snapshot versions.");

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            List<String> snapshotTables = listTablesInDataset(stmt, snapshotDataset);
            if (snapshotTables.isEmpty()) {
                throw new RuntimeException(
                        "No tables found in snapshot dataset '" + snapshotDataset + "'. Nothing to restore.");
            }
            log.info("Restoring {} tables: {}", snapshotTables.size(), snapshotTables);

            int failures = 0;
            for (String table : snapshotTables) {
                String restoreSql = String.format(
                        "CREATE OR REPLACE TABLE `%s.%s.%s` CLONE `%s.%s.%s`",
                        projectId, datasetId, table,
                        projectId, snapshotDataset, table);
                try {
                    stmt.execute(restoreSql);
                    log.info("  ✓ Restored: {}", table);
                } catch (Exception e) {
                    log.error("  ✗ Failed to restore {}: {}", table, e.getMessage());
                    failures++;
                }
            }

            if (failures > 0) {
                throw new RuntimeException(
                        "Restore FAILED: " + failures + " of " + snapshotTables.size() +
                                " table(s) could not be restored from '" + snapshotDataset + "'.");
            }
            log.info("Restore complete from {}", snapshotDataset);
        }
    }

    /**
     * Restore a SINGLE table from a snapshot dataset.
     * <p>
     * This is the safe restore option — it only overwrites the specified table,
     * leaving all other tables untouched.
     *
     * @param snapshotSuffix the suffix used when creating snapshots
     * @param tableName      the specific table to restore
     */
    public void restoreTableFromSnapshot(String snapshotSuffix, String tableName) throws Exception {
        String safeSuffix = snapshotSuffix.replaceAll("[^a-zA-Z0-9_]", "_");
        String snapshotDataset = datasetId + "_snapshots_" + safeSuffix;

        log.info("╔═══════════════════════════════════════════════════════════╗");
        log.info("║  Restoring Single Table from Snapshot                    ║");
        log.info("╠═══════════════════════════════════════════════════════════╣");
        log.info("║  Table     : {}", tableName);
        log.info("║  Source    : {}.{}", projectId, snapshotDataset);
        log.info("║  Target    : {}.{}", projectId, datasetId);
        log.info("╚═══════════════════════════════════════════════════════════╝");

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            // Verify the table exists in the snapshot dataset
            List<String> snapshotTables = listTablesInDataset(stmt, snapshotDataset);
            if (!snapshotTables.contains(tableName)) {
                throw new IllegalArgumentException(
                        "Table '" + tableName + "' not found in snapshot dataset '" + snapshotDataset + "'.\n" +
                                "Available tables: " + snapshotTables);
            }

            String restoreSql = String.format(
                    "CREATE OR REPLACE TABLE `%s.%s.%s` CLONE `%s.%s.%s`",
                    projectId, datasetId, tableName,
                    projectId, snapshotDataset, tableName);

            stmt.execute(restoreSql);
            log.info("✅ Restored table '{}' from snapshot '{}'", tableName, snapshotDataset);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // STRATEGY 2: Dataset Copy (permanent)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Create a full copy of all tables. Permanent — no expiration.
     * Incurs full storage costs but survives indefinitely.
     */
    public List<String> createDatasetCopy(String backupSuffix) throws Exception {
        String safeSuffix = backupSuffix.replaceAll("[^a-zA-Z0-9_]", "_");
        String backupDataset = datasetId + "_backup_" + safeSuffix;
        List<String> executedSql = new ArrayList<>();

        log.info("Creating dataset copy: {}.{}", projectId, backupDataset);

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            String createSql = String.format(
                    "CREATE SCHEMA IF NOT EXISTS `%s.%s`", projectId, backupDataset);
            stmt.execute(createSql);
            executedSql.add(createSql);

            List<String> tables = listTables(stmt);
            if (tables.isEmpty()) {
                log.info("No tables found. Skipping dataset copy.");
                return executedSql;
            }

            int failures = 0;
            for (String table : tables) {
                String copySql = String.format(
                        "CREATE TABLE `%s.%s.%s` AS SELECT * FROM `%s.%s.%s`",
                        projectId, backupDataset, table,
                        projectId, datasetId, table);
                try {
                    stmt.execute(copySql);
                    executedSql.add(copySql);
                    log.info("  ✓ Copied: {}", table);
                } catch (Exception e) {
                    log.warn("  ✗ Failed to copy {}: {}", table, e.getMessage());
                    failures++;
                }
            }

            if (failures > 0) {
                throw new RuntimeException("Dataset copy failed for " + failures + " tables. Aborting.");
            }

            log.info("Dataset copy complete — {} tables → {}", tables.size(), backupDataset);
        }

        return executedSql;
    }

    // ═══════════════════════════════════════════════════════════════════
    // STRATEGY 3: GCS Export (unlimited retention, cheapest storage)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Export all tables in the dataset to Google Cloud Storage.
     * <p>
     * This is the <b>best option for backups beyond 7 days</b> because:
     * <ul>
     * <li>GCS storage is significantly cheaper than BigQuery storage</li>
     * <li>No expiration — data persists until you delete it</li>
     * <li>Supports Parquet (default), Avro, CSV, and JSON formats</li>
     * <li>Can be loaded back into BigQuery with {@code bq load}</li>
     * <li>GCS lifecycle policies can auto-move to Coldline/Archive</li>
     * </ul>
     *
     * @param exportSuffix folder name suffix (e.g. "pre_release_v3")
     * @param format       export format: PARQUET, AVRO, CSV, or JSON (default:
     *                     PARQUET)
     * @return list of SQL statements executed
     */
    public List<String> exportToGcs(String exportSuffix, String format) throws Exception {
        if (gcsBucket == null || gcsBucket.isBlank()) {
            throw new IllegalStateException(
                    "BQ_BACKUP_GCS_BUCKET is not configured. Set it in your environment properties:\n" +
                            "  BQ_BACKUP_GCS_BUCKET=gs://your-backup-bucket");
        }

        String exportFormat = (format == null || format.isBlank()) ? "PARQUET" : format.toUpperCase();
        String safeSuffix = exportSuffix.replaceAll("[^a-zA-Z0-9_]", "_");
        String profile = getActiveProfile();
        String timestamp = TIMESTAMP_FMT.format(Instant.now());
        String gcsPath = String.format("%s/backups/%s/%s_%s",
                gcsBucket.replaceAll("/$", ""), profile, safeSuffix, timestamp);
        List<String> executedSql = new ArrayList<>();

        log.info("╔═══════════════════════════════════════════════════════════╗");
        log.info("║  Exporting to Google Cloud Storage                       ║");
        log.info("╠═══════════════════════════════════════════════════════════╣");
        log.info("║  Profile    : {}", profile);
        log.info("║  Source     : {}.{}", projectId, datasetId);
        log.info("║  GCS Path   : {}", gcsPath);
        log.info("║  Format     : {}", exportFormat);
        log.info("╚═══════════════════════════════════════════════════════════╝");

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            List<String> tables = listTables(stmt);
            if (tables.isEmpty()) {
                log.info("No tables found. Skipping GCS export.");
                return executedSql;
            }
            log.info("Found {} tables to export", tables.size());

            int failures = 0;
            for (String table : tables) {
                String tableGcsPath = String.format("%s/%s/*.%s",
                        gcsPath, table, exportFormat.toLowerCase());
                String exportSql = String.format(
                        "EXPORT DATA OPTIONS(\n" +
                                "  uri='%s',\n" +
                                "  format='%s',\n" +
                                "  overwrite=true\n" +
                                ") AS\n" +
                                "SELECT * FROM `%s.%s.%s`",
                        tableGcsPath, exportFormat,
                        projectId, datasetId, table);
                try {
                    stmt.execute(exportSql);
                    executedSql.add(exportSql);
                    log.info("  ✓ Exported: {} → {}", table, tableGcsPath);
                } catch (Exception e) {
                    log.warn("  ✗ Failed to export {}: {}", table, e.getMessage());
                    failures++;
                }
            }

            if (failures > 0) {
                throw new RuntimeException("GCS export failed for " + failures
                        + " tables. Check bucket permissions and location (must match BigQuery dataset location).");
            }

            log.info("╔═══════════════════════════════════════════════════════════╗");
            log.info("║  Export Complete                                          ║");
            log.info("║  {} tables → {}", tables.size(), gcsPath);
            log.info("║                                                           ║");
            log.info("║  To restore:                                              ║");
            log.info("║    bq load --source_format={} \\", exportFormat);
            log.info("║      {}.{}.TABLE_NAME \\", projectId, datasetId);
            log.info("║      {}/TABLE_NAME/*", gcsPath);
            log.info("╚═══════════════════════════════════════════════════════════╝");
        }

        return executedSql;
    }

    /**
     * Generate the {@code bq load} commands to restore from a GCS export.
     *
     * @param gcsPath path to the GCS export (e.g. gs://bucket/backups/dev1/...)
     * @param format  export format used (PARQUET, AVRO, CSV, JSON)
     * @return shell script with restore commands
     */
    public String generateGcsRestoreScript(String gcsPath, String format) throws Exception {
        String exportFormat = (format == null || format.isBlank()) ? "PARQUET" : format.toUpperCase();
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Restore from GCS backup: ").append(gcsPath).append("\n\n");

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            List<String> tables = listTables(stmt);
            for (String table : tables) {
                script.append(String.format(
                        "bq load --source_format=%s --replace \\\n" +
                                "  %s.%s.%s \\\n" +
                                "  %s/%s/*\n\n",
                        exportFormat,
                        projectId, datasetId, table,
                        gcsPath, table));
            }
        }

        log.info("Generated restore script for {} from {}", datasetId, gcsPath);
        return script.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // STRATEGY 4: Time Travel Query (built-in, up to 7 days)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Generate time-travel SQL for recovering data from a specific point in time.
     * BigQuery retains data for 7 days by default.
     */
    public String generateTimeTravelSql(String tableName, String timestamp) {
        String safeTableName = tableName.replaceAll("[^a-zA-Z0-9_]", "_");
        String sql = String.format(
                "-- Time Travel Recovery: %s at %s\n" +
                        "CREATE OR REPLACE TABLE `%s.%s.%s` AS\n" +
                        "SELECT * FROM `%s.%s.%s`\n" +
                        "FOR SYSTEM_TIME AS OF TIMESTAMP '%s'",
                safeTableName, timestamp,
                projectId, datasetId, safeTableName,
                projectId, datasetId, safeTableName,
                timestamp);
        log.info("Time-travel SQL generated for {} at {}", safeTableName, timestamp);
        return sql;
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTO BACKUP (for CI/CD)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Create an automatic pre-deploy backup using snapshots.
     *
     * @return the snapshot suffix (use this to restore if needed)
     */
    public String autoBackup() throws Exception {
        String timestamp = TIMESTAMP_FMT.format(Instant.now());
        String suffix = getActiveProfile() + "_" + timestamp;
        createTableSnapshots(suffix);
        return suffix;
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private List<String> listTables(Statement stmt) throws Exception {
        return listTablesInDataset(stmt, datasetId);
    }

    private List<String> listTablesInDataset(Statement stmt, String dataset) throws Exception {
        List<String> tables = new ArrayList<>();
        String sql = String.format(
                "SELECT table_name FROM `%s.%s.INFORMATION_SCHEMA.TABLES` WHERE table_type = 'BASE TABLE'",
                projectId, dataset);
        try (ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString("table_name"));
            }
        }
        return tables;
    }

    private String getActiveProfile() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1) {
            throw new IllegalStateException(
                    "Exactly one active Spring profile is required, but found: " + profiles.length);
        }
        return profiles[0];
    }
}
