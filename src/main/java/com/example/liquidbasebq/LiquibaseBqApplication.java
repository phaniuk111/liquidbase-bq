package com.example.liquidbasebq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Liquibase BigQuery Schema Manager
 * <p>
 * Spring Boot application that manages Google BigQuery schema using Liquibase.
 * On startup, Liquibase auto-runs all pending changesets defined in the
 * master changelog ({@code db/changelog/db.changelog-master.xml}).
 * <p>
 * Supports:
 * <ul>
 * <li>Create / drop tables (flat and nested STRUCT)</li>
 * <li>Add / drop / rename columns</li>
 * <li>Modify data types</li>
 * <li>Nested STRUCT/RECORD field operations (add, drop, modify inside
 * STRUCTs)</li>
 * <li>Create / drop views</li>
 * </ul>
 *
 * @see com.example.liquidbasebq.config.LiquibaseConfig
 * @see com.example.liquidbasebq.runner.SchemaManagerRunner
 */
@SpringBootApplication
public class LiquibaseBqApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiquibaseBqApplication.class, args);
    }
}
