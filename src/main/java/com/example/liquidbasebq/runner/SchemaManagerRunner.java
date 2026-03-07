package com.example.liquidbasebq.runner;

import com.example.liquidbasebq.service.BigQueryBackupService;
import com.example.liquidbasebq.service.BigQuerySchemaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * CLI runner for BigQuery schema management operations.
 * <p>
 * Supported actions:
 * 
 * <pre>
 *  --action=status            Show pending/applied changeset status
 *  --action=update            Apply all pending changesets
 *  --action=update-sql        Preview update SQL (dry-run)
 *  --action=validate          Validate changelog syntax
 *  --action=rollback          Rollback to a tag (requires --tag=TAG)
 *  --action=rollback-count    Rollback last N changesets (requires --count=N)
 *  --action=rollback-sql      Preview rollback SQL (requires --tag=TAG)
 *  --action=history           Show full deployment history
 *  --action=tag               Tag current state (requires --tag=TAG)
 *  --action=clear-checksums   Clear all stored checksums
 *  --action=backup            Snapshot all tables (requires --tag=SUFFIX)
 *  --action=backup-copy       Full dataset copy (requires --tag=SUFFIX)
 *  --action=restore           Restore ALL tables from snapshot (requires --tag=SUFFIX)
 *  --action=restore-table     Restore ONE table from snapshot (requires --tag=SUFFIX --table=NAME)
 *  --action=time-travel-sql   Generate time-travel SQL (requires --tag=TABLE --timestamp=TS)
 * </pre>
 */
@Component
public class SchemaManagerRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaManagerRunner.class);

    private final BigQuerySchemaService schemaService;
    private final BigQueryBackupService backupService;
    private final com.example.liquidbasebq.validation.ValidationConfig validationConfig;
    private final Environment environment;

    public SchemaManagerRunner(BigQuerySchemaService schemaService,
            BigQueryBackupService backupService,
            com.example.liquidbasebq.validation.ValidationConfig validationConfig,
            Environment environment) {
        this.schemaService = schemaService;
        this.backupService = backupService;
        this.validationConfig = validationConfig;
        this.environment = environment;
    }

    @Override
    public void run(String... args) throws Exception {
        String action = "status"; // default
        String tag = null;
        String table = null;
        int count = 1;
        String timestamp = null;
        String format = null;

        // Parse CLI arguments
        for (String arg : args) {
            if (arg.startsWith("--action=")) {
                action = arg.substring("--action=".length());
            } else if (arg.startsWith("--tag=")) {
                tag = arg.substring("--tag=".length());
            } else if (arg.startsWith("--table=")) {
                table = arg.substring("--table=".length());
            } else if (arg.startsWith("--count=")) {
                try {
                    count = Integer.parseInt(arg.substring("--count=".length()));
                    if (count <= 0) {
                        throw new IllegalArgumentException("Invalid value for --count it must be greater than 0");
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid value for --count it must be a valid integer, e.g., --count=3", e);
                }
            } else if (arg.startsWith("--timestamp=")) {
                timestamp = arg.substring("--timestamp=".length());
            } else if (arg.startsWith("--format=")) {
                format = arg.substring("--format=".length());
            }
        }

        String profile = getActiveProfile();

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("  BigQuery Schema Manager");
        log.info("  Environment : {}", profile.toUpperCase());
        log.info("  Action      : {}", action);
        log.info("═══════════════════════════════════════════════════════════════");

        switch (action.toLowerCase()) {
            case "validate-strict":
                log.info("Running strict changeset validation on [{}]...", profile);
                validationConfig.validateStrict();
                break;

            // ──────────── STATUS & HISTORY ────────────
            case "status":
                schemaService.getChangeSetStatus();
                break;

            case "history":
                schemaService.getHistory();
                break;

            // ──────────── UPDATE OPERATIONS ────────────
            case "update":
                log.info("Applying pending changesets to [{}]...", profile);
                schemaService.updateSchema();
                log.info("Schema update completed on [{}].", profile);
                break;

            case "update-sql":
                log.info("Generating update SQL for [{}] (dry-run)...", profile);
                String updateSql = schemaService.generateUpdateSql();
                log.info("Generated SQL for [{}]:\n{}", profile, updateSql);
                break;

            // ──────────── ROLLBACK OPERATIONS ────────────
            case "rollback":
                if (tag == null || tag.isBlank()) {
                    throw new IllegalArgumentException(
                            "Rollback by tag requires --tag=<TAG>. Example: --tag=baseline-v1\n" +
                                    "Use --action=history to see available tags.");
                }
                log.warn("⚠ Rolling back [{}] to tag: {}", profile, tag);
                schemaService.rollbackToTag(tag);
                log.info("Rollback to tag '{}' completed on [{}].", tag, profile);
                break;

            case "rollback-count":
                log.warn("⚠ Rolling back last {} changeset(s) on [{}]...", count, profile);
                schemaService.rollbackByCount(count);
                log.info("Rolled back {} changeset(s) on [{}].", count, profile);
                break;

            case "rollback-sql":
                if (tag == null || tag.isBlank()) {
                    throw new IllegalArgumentException(
                            "Rollback SQL preview requires --tag=<TAG>.");
                }
                log.info("Generating rollback SQL for [{}] to tag: {} ...", profile, tag);
                String rollbackSql = schemaService.generateRollbackSql(tag);
                log.info("Rollback SQL for [{}] to tag '{}':\n{}", profile, tag, rollbackSql);
                break;

            // ──────────── TAGGING ────────────
            case "tag":
                if (tag == null || tag.isBlank()) {
                    throw new IllegalArgumentException(
                            "Tag action requires --tag=<TAG>. Example: --tag=release-1.0");
                }
                log.info("Tagging [{}] as: {}", profile, tag);
                schemaService.tagDatabase(tag);
                log.info("Database [{}] tagged as '{}'.", profile, tag);
                break;

            // ──────────── BACKUP & RESTORE ────────────
            case "backup":
                String activeTag = tag;
                if (activeTag == null || activeTag.isBlank()) {
                    log.info("No --tag specified, using auto-generated suffix...");
                    activeTag = backupService.autoBackup();
                    log.info("Auto-backup completed. Snapshot suffix: {}", activeTag);
                } else {
                    backupService.createTableSnapshots(activeTag);
                    log.info("Snapshot backup completed.");
                }
                log.info("To restore: --action=restore --tag={}", activeTag);

                // Write metadata for CI/CD artifact
                try {
                    java.io.File targetDir = new java.io.File(System.getProperty("user.dir"), "target");
                    if (!targetDir.exists())
                        targetDir.mkdirs();
                    java.nio.file.Files.writeString(
                            new java.io.File(targetDir, "backup-metadata.properties").toPath(),
                            "BACKUP_PROFILE=" + profile + "\nBACKUP_SUFFIX=" + activeTag + "\nBACKUP_TIMESTAMP="
                                    + java.time.Instant.now().toString() + "\n");
                } catch (Exception e) {
                    log.warn("Failed to write backup-metadata.properties", e);
                }
                break;

            case "backup-copy":
                if (tag == null || tag.isBlank()) {
                    throw new IllegalArgumentException(
                            "Dataset copy requires --tag=<SUFFIX>. Example: --tag=pre_release_v2");
                }
                backupService.createDatasetCopy(tag);
                log.info("Dataset copy completed with suffix: {}", tag);
                break;

            case "restore":
                if (tag == null || tag.isBlank()) {
                    throw new IllegalArgumentException(
                            "Restore requires --tag=<SUFFIX> from a previous backup.\n" +
                                    "Example: --action=restore --tag=dev1_20260306_120000\n" +
                                    "Tip: To restore a single table, use --action=restore-table --tag=SUFFIX --table=TABLE_NAME");
                }
                log.warn("⚠ Restoring ALL tables on [{}] from snapshot: {}", profile, tag);
                backupService.restoreFromSnapshots(tag);
                log.info("Restore from snapshot '{}' completed on [{}].", tag, profile);
                break;

            case "restore-table":
                if (tag == null || tag.isBlank() || table == null || table.isBlank()) {
                    throw new IllegalArgumentException(
                            "Single-table restore requires --tag=<SUFFIX> --table=<TABLE_NAME>\n" +
                                    "Example: --action=restore-table --tag=dev1_20260306_120000 --table=payment_methods");
                }
                log.warn("⚠ Restoring single table '{}' on [{}] from snapshot: {}", table, profile, tag);
                backupService.restoreTableFromSnapshot(tag, table);
                log.info("Single-table restore of '{}' from snapshot '{}' completed on [{}].", table, tag, profile);
                break;

            case "time-travel-sql":
                if (tag == null || tag.isBlank() || timestamp == null || timestamp.isBlank()) {
                    throw new IllegalArgumentException(
                            "Time-travel requires --tag=<TABLE_NAME> --timestamp=<TIMESTAMP>\n" +
                                    "Example: --tag=orders --timestamp=\"2026-03-06 04:00:00 UTC\"");
                }
                String ttSql = backupService.generateTimeTravelSql(tag, timestamp);
                log.info("Time-travel recovery SQL:\n{}", ttSql);
                break;

            // ──────────── GCS EXPORT (long-term) ────────────
            case "export-gcs":
                if (tag == null || tag.isBlank()) {
                    throw new IllegalArgumentException(
                            "GCS export requires --tag=<SUFFIX>. Example: --tag=pre_release_v3\n" +
                                    "Optional: --format=PARQUET|AVRO|CSV|JSON (default: PARQUET)");
                }
                log.info("Exporting [{}] to GCS with suffix: {} (format: {})", profile, tag,
                        format != null ? format : "PARQUET");
                backupService.exportToGcs(tag, format);
                log.info("GCS export completed.");
                break;

            case "restore-gcs-script":
                if (tag == null || tag.isBlank()) {
                    throw new IllegalArgumentException(
                            "Requires --tag=<GCS_PATH>. Example: --tag=gs://bucket/backups/dev1/...");
                }
                String script = backupService.generateGcsRestoreScript(tag, format);
                log.info("Restore script:\n{}", script);
                break;

            // ──────────── MAINTENANCE ────────────
            case "validate":
                log.info("Validating changelog...");
                schemaService.validateChangelog();
                log.info("Changelog validation passed.");
                break;

            case "clear-checksums":
                log.warn("⚠ Clearing all checksums on [{}]...", profile);
                schemaService.clearCheckSums();
                log.info("Checksums cleared on [{}].", profile);
                break;

            default:
                throw new IllegalArgumentException(
                        "Unknown action '" + action + "'. Supported actions:\n" +
                                "  status, history, update, update-sql,\n" +
                                "  rollback, rollback-count, rollback-sql,\n" +
                                "  tag, validate, clear-checksums,\n" +
                                "  backup, backup-copy, restore, restore-table, time-travel-sql,\n" +
                                "  export-gcs, restore-gcs-script");
        }

        log.info("═══════════════════════════════════════════════════════════════");
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
