package com.example.liquidbasebq.config;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LiquibaseConfig}.
 */
@ExtendWith(MockitoExtension.class)
class LiquibaseConfigTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Environment environment;

    @Test
    @DisplayName("should create SpringLiquibase bean successfully")
    void shouldCreateBeanWithAutoRunDisabled() throws Exception {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "dev1" });

        LiquibaseConfig config = new LiquibaseConfig(environment);

        // Set fields via reflection since @Value won't be processed in unit tests
        setField(config, "changeLog", "classpath:db/changelog/db.changelog-master.xml");
        setField(config, "datasourceUrl", "jdbc:bigquery://host;ProjectId=test-project;DefaultDataset=test_dataset");

        SpringLiquibase liquibase = config.liquibase(dataSource);

        assertNotNull(liquibase);
        // Contexts should be set from the active profile
        assertEquals("dev1", liquibase.getContexts());
    }

    @Test
    @DisplayName("should set contexts from active Spring profile")
    void shouldSetContextsFromProfile() throws Exception {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "uat1" });

        LiquibaseConfig config = new LiquibaseConfig(environment);
        setField(config, "changeLog", "classpath:db/changelog/db.changelog-master.xml");
        setField(config, "datasourceUrl", "jdbc:bigquery://host;ProjectId=test;DefaultDataset=test");

        SpringLiquibase liquibase = config.liquibase(dataSource);

        assertNotNull(liquibase);
        assertEquals("uat1", liquibase.getContexts());
    }

    @Test
    @DisplayName("should handle no active profiles gracefully")
    void shouldHandleNoActiveProfiles() throws Exception {
        when(environment.getActiveProfiles()).thenReturn(new String[] {});

        LiquibaseConfig config = new LiquibaseConfig(environment);
        setField(config, "changeLog", "classpath:db/changelog/db.changelog-master.xml");
        setField(config, "datasourceUrl", "jdbc:bigquery://host");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> config.liquibase(dataSource));
        assertTrue(e.getMessage().contains("Exactly one active Spring"));
    }

    @Test
    @DisplayName("should handle multiple active profiles")
    void shouldHandleMultipleProfiles() throws Exception {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "dev1", "local" });

        LiquibaseConfig config = new LiquibaseConfig(environment);
        setField(config, "changeLog", "classpath:db/changelog/db.changelog-master.xml");
        setField(config, "datasourceUrl", "jdbc:bigquery://host;ProjectId=p;DefaultDataset=d");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> config.liquibase(dataSource));
        assertTrue(e.getMessage().contains("Exactly one active Spring"));
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
