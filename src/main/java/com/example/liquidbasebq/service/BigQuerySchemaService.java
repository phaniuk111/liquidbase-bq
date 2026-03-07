package com.example.liquidbasebq.service;

import liquibase.Liquibase;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.changelog.ChangeSetStatus;
import liquibase.changelog.RanChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.util.List;

/**
 * Service wrapping Liquibase API for programmatic BigQuery schema management.
 * <p>
 * Supports:
 * <ul>
 * <li>{@link #updateSchema()} — apply all pending changesets</li>
 * <li>{@link #rollbackToTag(String)} — rollback to a named tag</li>
 * <li>{@link #rollbackByCount(int)} — rollback last N changesets</li>
 * <li>{@link #generateRollbackSql(String)} — preview rollback SQL</li>
 * <li>{@link #getChangeSetStatus()} — list pending/applied changesets</li>
 * <li>{@link #generateUpdateSql()} — preview update SQL</li>
 * <li>{@link #validateChangelog()} — validate changelog XML</li>
 * <li>{@link #tagDatabase(String)} — tag current state for rollback</li>
 * <li>{@link #getHistory()} — show full deployment history</li>
 * <li>{@link #clearCheckSums()} — clear all checksums</li>
 * </ul>
 */
@Service
public class BigQuerySchemaService {

    private static final Logger log = LoggerFactory.getLogger(BigQuerySchemaService.class);

    private final DataSource dataSource;
    private final Environment environment;

    @Value("${spring.liquibase.change-log:classpath:db/changelog/db.changelog-master.xml}")
    private String changeLogFile;

    public BigQuerySchemaService(DataSource dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    // ───────────────────────────────────────────────────────────────────
    // UPDATE OPERATIONS
    // ───────────────────────────────────────────────────────────────────

    /**
     * Apply all pending changesets to BigQuery.
     */
    public void updateSchema() throws Exception {
        String profile = getActiveProfile();
        log.info("Applying pending changesets to [{}] environment...", profile);
        try (Liquibase liquibase = createLiquibase()) {
            liquibase.update(new Contexts(profile), new LabelExpression());
            log.info("All pending changesets applied successfully to [{}].", profile);
        }
    }

    /**
     * Generate the SQL that would be executed by {@link #updateSchema()},
     * without actually applying it. Useful for code-review / dry-run.
     */
    @SuppressWarnings("deprecation")
    public String generateUpdateSql() throws Exception {
        try (Liquibase liquibase = createLiquibase()) {
            Writer writer = new StringWriter();
            liquibase.update(new Contexts(getActiveProfile()), new LabelExpression(), writer);
            return writer.toString();
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // ROLLBACK OPERATIONS
    // ───────────────────────────────────────────────────────────────────

    /**
     * Rollback the database to a previously applied tag.
     *
     * @param tag the tag name (e.g. "baseline-v1")
     */
    public void rollbackToTag(String tag) throws Exception {
        String profile = getActiveProfile();
        log.info("Rolling back [{}] to tag: {}", profile, tag);
        try (Liquibase liquibase = createLiquibase()) {
            boolean tagExists = liquibase.getDatabase().getRanChangeSetList().stream()
                    .filter(ran -> ran.getTag() != null)
                    .anyMatch(ran -> ran.getTag().equals(tag));
            if (!tagExists) {
                throw new IllegalArgumentException(
                        "Rollback failed: tag '" + tag + "' does not exist in the database.");
            }

            liquibase.rollback(tag, new Contexts(profile), new LabelExpression());
            log.info("Rolled back [{}] to tag: {}", profile, tag);
        }
    }

    /**
     * Rollback the last N changesets.
     *
     * @param count number of changesets to rollback
     */
    public void rollbackByCount(int count) throws Exception {
        String profile = getActiveProfile();
        if ("prd".equalsIgnoreCase(profile)) {
            throw new UnsupportedOperationException(
                    "rollback-count is strictly blocked in production. Use rollback by tag.");
        }
        log.info("Rolling back last {} changeset(s) on [{}]...", count, profile);
        try (Liquibase liquibase = createLiquibase()) {
            liquibase.rollback(count, new Contexts(profile), new LabelExpression());
            log.info("Rolled back {} changeset(s) on [{}].", count, profile);
        }
    }

    /**
     * Generate the SQL that would be executed by a rollback to the given tag,
     * without actually rolling back.
     *
     * @param tag the tag name to rollback to
     * @return the rollback SQL
     */
    public String generateRollbackSql(String tag) throws Exception {
        try (Liquibase liquibase = createLiquibase()) {
            Writer writer = new StringWriter();
            liquibase.rollback(tag, new Contexts(getActiveProfile()), new LabelExpression(), writer);
            return writer.toString();
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // STATUS & HISTORY
    // ───────────────────────────────────────────────────────────────────

    /**
     * Print the status of all changesets — which are pending and which are applied.
     */
    public void getChangeSetStatus() throws Exception {
        try (Liquibase liquibase = createLiquibase()) {
            List<ChangeSetStatus> statusList = liquibase.getChangeSetStatuses(
                    new Contexts(getActiveProfile()), new LabelExpression());

            int applied = 0;
            int pending = 0;

            log.info("┌─────────────────────────────────────────────────────────────────────┐");
            log.info("│  Changeset Status — [{}]", getActiveProfile());
            log.info("├─────────────────────────────────────────────────────────────────────┤");

            for (ChangeSetStatus status : statusList) {
                String state = status.getWillRun() ? "PENDING" : "APPLIED";
                if (status.getWillRun()) {
                    pending++;
                } else {
                    applied++;
                }
                log.info("│  {} | {} :: {}",
                        state,
                        status.getChangeSet().getId(),
                        status.getChangeSet().getAuthor());
            }

            log.info("├─────────────────────────────────────────────────────────────────────┤");
            log.info("│  Total: {} applied, {} pending", applied, pending);
            log.info("└─────────────────────────────────────────────────────────────────────┘");
        }
    }

    /**
     * Show the full deployment history — all changesets that have been applied,
     * with timestamps and tags.
     */
    public void getHistory() throws Exception {
        try (Liquibase liquibase = createLiquibase()) {
            List<RanChangeSet> history = liquibase.getDatabase()
                    .getRanChangeSetList();

            log.info("┌─────────────────────────────────────────────────────────────────────┐");
            log.info("│  Deployment History — [{}]", getActiveProfile());
            log.info("├─────────────────────────────────────────────────────────────────────┤");
            log.info("│  {:<35} {:<15} {:<12} {}",
                    "CHANGESET ID", "AUTHOR", "DATE", "TAG");
            log.info("├─────────────────────────────────────────────────────────────────────┤");

            for (RanChangeSet ranChangeSet : history) {
                String tag = ranChangeSet.getTag() != null ? ranChangeSet.getTag() : "";
                String date = ranChangeSet.getDateExecuted() != null
                        ? ranChangeSet.getDateExecuted().toString()
                        : "unknown";
                log.info("│  {:<35} {:<15} {:<12} {}",
                        ranChangeSet.getId(),
                        ranChangeSet.getAuthor(),
                        date,
                        tag);
            }

            log.info("├─────────────────────────────────────────────────────────────────────┤");
            log.info("│  Total: {} changesets deployed", history.size());
            log.info("└─────────────────────────────────────────────────────────────────────┘");
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // MAINTENANCE
    // ───────────────────────────────────────────────────────────────────

    /**
     * Validate the changelog XML — checks for syntax errors, missing files,
     * and unsupported change types.
     */
    public void validateChangelog() throws Exception {
        try (Liquibase liquibase = createLiquibase()) {
            liquibase.validate();
            log.info("Changelog validation completed — no errors found.");
        }
    }

    /**
     * Tag the current database state. Tags are used as rollback targets.
     *
     * @param tag a descriptive tag name (e.g. "release-2.0")
     */
    public void tagDatabase(String tag) throws Exception {
        try (Liquibase liquibase = createLiquibase()) {
            liquibase.tag(tag);
            log.info("Database tagged as: {}", tag);
        }
    }

    /**
     * Clear all stored checksums. This forces Liquibase to re-compute checksums
     * on the next run. Useful after manually fixing a changelog entry.
     */
    public void clearCheckSums() throws Exception {
        try (Liquibase liquibase = createLiquibase()) {
            liquibase.clearCheckSums();
            log.info("All checksums cleared.");
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // INTERNAL HELPERS
    // ───────────────────────────────────────────────────────────────────

    /**
     * Returns the active Spring profile (e.g. "dev", "prl1", "uat", "prd").
     */
    private String getActiveProfile() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1) {
            throw new IllegalStateException(
                    "Exactly one active Spring profile is required, but found: " + profiles.length);
        }
        return profiles[0];
    }

    /**
     * Creates a configured {@link Liquibase} instance connected to BigQuery.
     */
    public Liquibase createLiquibase() throws Exception {
        Connection connection = dataSource.getConnection();
        Database database;
        try {
            database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        } catch (Exception e) {
            connection.close();
            throw e;
        }

        String resolvedChangeLog = changeLogFile.replace("classpath:", "");

        return new Liquibase(resolvedChangeLog,
                new ClassLoaderResourceAccessor(),
                database);
    }
}
