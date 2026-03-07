package com.example.liquidbasebq.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.change.Change;
import liquibase.change.core.RawSQLChange;
import liquibase.change.core.TagDatabaseChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service to strictly validate Liquibase changesets against security and
 * governance rules.
 * Runs exclusively when invoked via `--action=validate-strict`.
 */
@Service
public class ValidationConfig {

    private static final Logger log = LoggerFactory.getLogger(ValidationConfig.class);
    @org.springframework.beans.factory.annotation.Value("${spring.liquibase.change-log:classpath:db/changelog/db.changelog-master.xml}")
    private String changeLogFile;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Data structure to represent an allowlisted legacy changeset
    public static class AllowlistEntry {
        public String id;
        public String author;
        public String filePath;

        public String getCanonicalKey() {
            return id + "|" + author + "|" + filePath.replace("classpath:", "").replaceAll("^/", "");
        }
    }

    public ValidationConfig() {
    }

    /**
     * Executes strict validation on all changesets in the changelog.
     * 
     * @throws IllegalStateException if validation fails for any non-allowlisted
     *                               changeset.
     */
    public void validateStrict() throws Exception {
        log.info("Starting strict Liquibase changeset validation...");

        Set<String> allowlist = loadAllowlist();
        String resolvedLog = changeLogFile.replace("classpath:", "");
        liquibase.resource.ResourceAccessor accessor = new liquibase.resource.ClassLoaderResourceAccessor();
        DatabaseChangeLog changeLog = liquibase.parser.ChangeLogParserFactory.getInstance()
                .getParser(resolvedLog, accessor)
                .parse(resolvedLog, new liquibase.changelog.ChangeLogParameters(), accessor);

        List<String> violations = new ArrayList<>();

        for (ChangeSet changeSet : changeLog.getChangeSets()) {
            String key = changeSet.getId() + "|" + changeSet.getAuthor() + "|"
                    + changeSet.getFilePath().replace("classpath:", "").replaceAll("^/", "");

            if (allowlist.contains(key)) {
                log.debug("Skipping validation for allowlisted changeset: {}", key);
                continue;
            }

            List<String> changesetViolations = validateChangeSet(changeSet);
            if (!changesetViolations.isEmpty()) {
                violations.add("ChangeSet: " + changeSet.getFilePath().replace("classpath:", "") + " (id: "
                        + changeSet.getId() + ")");
                changesetViolations.forEach(v -> violations.add(v));
                violations.add("\n──────────────────────────────────────────────────");
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder("\n\n🚨 STRICT VALIDATION FAILED 🚨\n");
            msg.append("The following changesets violate repository governance rules:\n\n");
            msg.append(String.join("\n", violations));
            msg.append("\n\nFix these issues in your XML file and run validation again:\n");
            msg.append(
                    "   ./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.arguments=\"--action=validate-strict\"\n");

            log.error(msg.toString());
            throw new IllegalStateException(msg.toString());
        }

        log.info("✅ All changesets passed strict validation successfully.");
    }

    private List<String> validateChangeSet(ChangeSet changeSet) {
        List<String> errors = new ArrayList<>();

        // Check 1: Is it a tag-only changeset?
        boolean isTagOnly = changeSet.getChanges().stream().allMatch(c -> c instanceof TagDatabaseChange);

        if (!isTagOnly) {
            // Check 2: Rollback block must exist
            if (changeSet.getRollback() == null || changeSet.getRollback().getChanges() == null
                    || changeSet.getRollback().getChanges().isEmpty()) {
                errors.add("  ❌ Rule: Missing <rollback> block.\n" +
                        "     Remediation: All non-tag changesets must define explicit rollback steps.\n" +
                        "     Example:\n" +
                        "       <rollback>\n" +
                        "           <sqlFile path=\"db/changelog/sql/rollback/...\" splitStatements=\"true\" stripComments=\"true\"/>\n"
                        +
                        "       </rollback>");
            }
        }

        // Check 3: Context must be defined
        if (changeSet.getContextFilter() == null || changeSet.getContextFilter().isEmpty()) {
            errors.add("  ❌ Rule: Missing 'context' attribute.\n" +
                    "     Remediation: Define valid deployment environments.\n" +
                    "     Example: <changeSet id=\"" + changeSet.getId() + "\" author=\"" + changeSet.getAuthor()
                    + "\" context=\"dev,uat,prd\">");
        }

        // Check 4: Labels must be defined
        if (changeSet.getLabels() == null || changeSet.getLabels().isEmpty()) {
            errors.add("  ❌ Rule: Missing 'labels' attribute.\n" +
                    "     Remediation: Add a Jira ticket or feature label.\n" +
                    "     Example: <changeSet id=\"" + changeSet.getId() + "\" author=\"" + changeSet.getAuthor()
                    + "\" labels=\"JIRA-123\">");
        }

        // Check 5: Destructive SQL requires Preconditions
        boolean hasDestructiveSql = false;
        for (Change change : changeSet.getChanges()) {
            if (change.getClass().getSimpleName().contains("Drop")
                    || change.getClass().getSimpleName().contains("Delete")) {
                hasDestructiveSql = true;
            }
            if (change instanceof RawSQLChange) {
                String sql = ((RawSQLChange) change).getSql().toUpperCase();
                if (sql.contains("DROP TABLE") || sql.contains("TRUNCATE") || sql.contains("DELETE FROM")
                        || sql.contains("DROP COLUMN")) {
                    hasDestructiveSql = true;
                }
            }
            if (change instanceof liquibase.change.core.SQLFileChange) {
                liquibase.change.core.SQLFileChange sqlFileChange = (liquibase.change.core.SQLFileChange) change;
                try (java.io.InputStream is = new org.springframework.core.io.ClassPathResource(sqlFileChange.getPath())
                        .getInputStream()) {
                    String sql = new String(is.readAllBytes()).toUpperCase();
                    if (sql.contains("DROP TABLE") || sql.contains("TRUNCATE") || sql.contains("DELETE FROM")
                            || sql.contains("DROP COLUMN")) {
                        hasDestructiveSql = true;
                    }
                } catch (Exception e) {
                    errors.add("  ❌ Rule: Unreadable SQL file detected.\n" +
                            "     Remediation: SQL files must be readable during strict validation to check for destructive operations.\n"
                            +
                            "     File: " + sqlFileChange.getPath());
                }
            }
        }

        if (hasDestructiveSql) {
            if (changeSet.getPreconditions() == null
                    || changeSet.getPreconditions().getNestedPreconditions().isEmpty()) {
                errors.add("  ❌ Rule: Destructive operation detected without <preConditions>.\n" +
                        "     Remediation: Protect against data loss by verifying the state before dropping.\n" +
                        "     Example:\n" +
                        "       <preConditions onFail=\"MARK_RAN\">\n" +
                        "           <columnExists tableName=\"my_table\" columnName=\"old_column\"/>\n" +
                        "       </preConditions>");
            }
        }

        return errors;
    }

    private Set<String> loadAllowlist() {
        try {
            ClassPathResource resource = new ClassPathResource("db/changelog/liquibase-legacy-allowlist.json");
            if (!resource.exists()) {
                log.warn(
                        "Allowlist file not found at db/changelog/liquibase-legacy-allowlist.json. Proceeding with empty allowlist.");
                return Set.of();
            }
            try (InputStream is = resource.getInputStream()) {
                List<AllowlistEntry> entries = objectMapper.readValue(is, new TypeReference<List<AllowlistEntry>>() {
                });
                return entries.stream()
                        .map(AllowlistEntry::getCanonicalKey)
                        .collect(Collectors.toSet());
            }
        } catch (Exception e) {
            log.error("Failed to parse allowlist JSON", e);
            throw new RuntimeException("Failed to load liquibase-legacy-allowlist.json", e);
        }
    }
}
