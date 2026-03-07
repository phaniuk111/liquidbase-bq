package com.example.liquidbasebq.config;

import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Arrays;

/**
 * Profile-aware Liquibase configuration.
 * <p>
 * Logs the active Spring profile and target GCP project/dataset on startup.
 * Liquibase auto-run is disabled by default
 * ({@code spring.liquibase.enabled=false});
 * schema changes are applied programmatically via {@code SchemaManagerRunner}.
 */
@Configuration
public class LiquibaseConfig {

    private static final Logger log = LoggerFactory.getLogger(LiquibaseConfig.class);

    private final Environment environment;

    @Value("${spring.liquibase.change-log:classpath:db/changelog/db.changelog-master.xml}")
    private String changeLog;

    @Value("${spring.datasource.url:not-configured}")
    private String datasourceUrl;

    public LiquibaseConfig(Environment environment) {
        this.environment = environment;
    }

    @jakarta.annotation.PostConstruct
    public void validateDriverPresence() {
        try {
            Class.forName("com.simba.googlebigquery.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            String msg = "\n\n" +
                    "=================================================================================\n" +
                    "CRITICAL ERROR: BigQuery Simba JDBC Driver (com.simba.googlebigquery.jdbc.Driver) is missing.\n" +
                    "Because Simba is proprietary, it is not available on Maven Central.\n" +
                    "\n" +
                    "To fix this locally:\n" +
                    "1. Download GoogleBigQueryJDBC42.jar.\n" +
                    "2. Install it to your local Maven repository:\n" +
                    "   mvn install:install-file -Dfile=/path/to/GoogleBigQueryJDBC42.jar \\\n" +
                    "       -DgroupId=com.simba.googlebigquery.jdbc \\\n" +
                    "       -DartifactId=GoogleBigQueryJDBC42 -Dversion=1.6.3.1004 -Dpackaging=jar\n" +
                    "=================================================================================\n";
            throw new org.springframework.beans.factory.BeanInitializationException(msg, e);
        }
    }

    /**
     * Creates a {@link SpringLiquibase} bean wired to the BigQuery DataSource.
     * <p>
     * The bean is created but {@code shouldRun} is set to false because
     * schema operations are driven by the CLI runner (--action flag).
     *
     * @param dataSource the BigQuery JDBC DataSource auto-configured by Spring Boot
     * @return configured SpringLiquibase instance
     */
    @Bean
    public SpringLiquibase liquibase(DataSource dataSource) {
        String[] activeProfiles = environment.getActiveProfiles();
        String profileName = activeProfiles.length > 0
                ? String.join(", ", activeProfiles)
                : "default";

        // Extract project and dataset from JDBC URL for logging
        String projectId = extractUrlParam(datasourceUrl, "ProjectId");
        String datasetId = extractUrlParam(datasourceUrl, "DefaultDataset");

        log.info("╔═══════════════════════════════════════════════════════════╗");
        log.info("║  Liquibase BigQuery Schema Manager                       ║");
        log.info("╠═══════════════════════════════════════════════════════════╣");
        log.info("║  Active Profile : {}", profileName);
        log.info("║  GCP Project    : {}", projectId);
        log.info("║  BQ Dataset     : {}", datasetId);
        log.info("║  Changelog      : {}", changeLog);
        log.info("╚═══════════════════════════════════════════════════════════╝");

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);

        // Auto-run is disabled — schema operations are controlled via
        // SchemaManagerRunner with --action=update|rollback|status|etc.
        liquibase.setShouldRun(false);

        // Set Liquibase contexts matching the active Spring profile
        // so changesets can use context="dev" or context="prd" attributes.
        if (activeProfiles.length > 0) {
            liquibase.setContexts(String.join(",", activeProfiles));
        }

        return liquibase;
    }

    /**
     * Extract a parameter value from a BigQuery JDBC URL.
     * URL format: jdbc:bigquery://...;ParamName=Value;...
     */
    private String extractUrlParam(String url, String paramName) {
        if (url == null)
            return "unknown";
        return Arrays.stream(url.split(";"))
                .filter(p -> p.trim().startsWith(paramName + "="))
                .map(p -> p.trim().substring(paramName.length() + 1))
                .findFirst()
                .orElse("unknown");
    }
}
