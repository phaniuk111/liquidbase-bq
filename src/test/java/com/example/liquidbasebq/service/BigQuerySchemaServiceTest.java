package com.example.liquidbasebq.service;

import liquibase.Liquibase;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.ChangeSetStatus;
import liquibase.changelog.RanChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.io.Writer;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BigQuerySchemaService}.
 * <p>
 * Uses Mockito to mock JDBC DataSource and Liquibase internals
 * so tests run without a real BigQuery connection.
 */
@ExtendWith(MockitoExtension.class)
class BigQuerySchemaServiceTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private Database database;

    @Mock
    private Environment environment;

    @Mock
    private Liquibase liquibase;

    private BigQuerySchemaService service;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[] { "dev1" });
        service = new BigQuerySchemaService(dataSource, environment);

        // Use reflection to set the changeLogFile since @Value won't be processed
        var field = BigQuerySchemaService.class.getDeclaredField("changeLogFile");
        field.setAccessible(true);
        field.set(service, "classpath:db/changelog/db.changelog-master.xml");
    }

    @Nested
    @DisplayName("updateSchema()")
    class UpdateSchemaTests {

        @Test
        @DisplayName("should apply pending changesets with active profile context")
        void shouldApplyPendingChangesets() throws Exception {
            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> doNothing().when(mock).update(any(Contexts.class), any(LabelExpression.class)))) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    assertDoesNotThrow(() -> service.updateSchema());
                }
            }
        }
    }

    @Nested
    @DisplayName("rollbackToTag()")
    class RollbackToTagTests {

        @Test
        @DisplayName("should rollback to specified tag")
        void shouldRollbackToTag() throws Exception {
            RanChangeSet ran1 = mock(RanChangeSet.class);
            when(ran1.getTag()).thenReturn("baseline-v1");

            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> {
                        when(mock.getDatabase()).thenReturn(database);
                        doNothing().when(mock).rollback(anyString(), any(Contexts.class), any(LabelExpression.class));
                    })) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);
                    when(database.getRanChangeSetList()).thenReturn(Collections.singletonList(ran1));

                    assertDoesNotThrow(() -> service.rollbackToTag("baseline-v1"));
                }
            }
        }
    }

    @Nested
    @DisplayName("rollbackByCount()")
    class RollbackByCountTests {

        @Test
        @DisplayName("should rollback last N changesets")
        void shouldRollbackByCount() throws Exception {
            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> doNothing().when(mock).rollback(anyInt(), any(Contexts.class),
                            any(LabelExpression.class)))) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    assertDoesNotThrow(() -> service.rollbackByCount(3));
                }
            }
        }
    }

    @Nested
    @DisplayName("generateUpdateSql()")
    class GenerateUpdateSqlTests {

        @SuppressWarnings("deprecation")
        @Test
        @DisplayName("should return generated SQL string")
        void shouldReturnSql() throws Exception {
            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> doAnswer(invocation -> {
                        Writer writer = invocation.getArgument(2);
                        writer.write("CREATE TABLE test (id INT64);");
                        return null;
                    }).when(mock).update(any(Contexts.class), any(LabelExpression.class), any(Writer.class)))) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    String sql = service.generateUpdateSql();
                    assertNotNull(sql);
                    assertTrue(sql.contains("CREATE TABLE"));
                }
            }
        }
    }

    @Nested
    @DisplayName("generateRollbackSql()")
    class GenerateRollbackSqlTests {

        @Test
        @DisplayName("should return rollback SQL for a given tag")
        void shouldReturnRollbackSql() throws Exception {
            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> doAnswer(invocation -> {
                        Writer writer = invocation.getArgument(3);
                        writer.write("DROP TABLE test;");
                        return null;
                    }).when(mock).rollback(anyString(), any(Contexts.class), any(LabelExpression.class),
                            any(Writer.class)))) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    String sql = service.generateRollbackSql("baseline-v1");
                    assertNotNull(sql);
                    assertTrue(sql.contains("DROP TABLE"));
                }
            }
        }
    }

    @Nested
    @DisplayName("getChangeSetStatus()")
    class GetChangeSetStatusTests {

        @Test
        @DisplayName("should list pending and applied changesets")
        void shouldListChangesets() throws Exception {
            ChangeSet cs1 = mock(ChangeSet.class);
            when(cs1.getId()).thenReturn("001-create-tables");
            when(cs1.getAuthor()).thenReturn("admin");

            ChangeSetStatus status1 = mock(ChangeSetStatus.class);
            when(status1.getWillRun()).thenReturn(false);
            when(status1.getChangeSet()).thenReturn(cs1);

            ChangeSet cs2 = mock(ChangeSet.class);
            when(cs2.getId()).thenReturn("002-add-columns");
            when(cs2.getAuthor()).thenReturn("admin");

            ChangeSetStatus status2 = mock(ChangeSetStatus.class);
            when(status2.getWillRun()).thenReturn(true);
            when(status2.getChangeSet()).thenReturn(cs2);

            List<ChangeSetStatus> statuses = Arrays.asList(status1, status2);

            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> when(mock.getChangeSetStatuses(any(Contexts.class), any(LabelExpression.class)))
                            .thenReturn(statuses))) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    assertDoesNotThrow(() -> service.getChangeSetStatus());
                }
            }
        }
    }

    @Nested
    @DisplayName("getHistory()")
    class GetHistoryTests {

        @Test
        @DisplayName("should display deployment history")
        void shouldDisplayHistory() throws Exception {
            RanChangeSet ran1 = mock(RanChangeSet.class);
            when(ran1.getId()).thenReturn("001-create-tables");
            when(ran1.getAuthor()).thenReturn("admin");
            when(ran1.getDateExecuted()).thenReturn(new Date());
            when(ran1.getTag()).thenReturn("baseline-v1");

            List<RanChangeSet> history = Collections.singletonList(ran1);

            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> {
                        Database mockDb = mock(Database.class);
                        when(mockDb.getRanChangeSetList()).thenReturn(history);
                        when(mock.getDatabase()).thenReturn(mockDb);
                    })) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    assertDoesNotThrow(() -> service.getHistory());
                }
            }
        }
    }

    @Nested
    @DisplayName("validateChangelog()")
    class ValidateChangelogTests {

        @Test
        @DisplayName("should validate without errors")
        void shouldValidateSuccessfully() throws Exception {
            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> doNothing().when(mock).validate())) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    assertDoesNotThrow(() -> service.validateChangelog());
                }
            }
        }
    }

    @Nested
    @DisplayName("tagDatabase()")
    class TagDatabaseTests {

        @Test
        @DisplayName("should tag database with given name")
        void shouldTagDatabase() throws Exception {
            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> doNothing().when(mock).tag(anyString()))) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    assertDoesNotThrow(() -> service.tagDatabase("release-1.0"));
                }
            }
        }
    }

    @Nested
    @DisplayName("clearCheckSums()")
    class ClearCheckSumsTests {

        @Test
        @DisplayName("should clear all checksums")
        void shouldClearChecksums() throws Exception {
            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> doNothing().when(mock).clearCheckSums())) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    assertDoesNotThrow(() -> service.clearCheckSums());
                }
            }
        }
    }

    @Nested
    @DisplayName("Profile handling")
    class ProfileHandlingTests {

        @Test
        @DisplayName("should use 'default' when no active profile")
        void shouldUseDefaultProfile() throws Exception {
            when(environment.getActiveProfiles()).thenReturn(new String[] {});

            try (MockedConstruction<Liquibase> ignored = mockConstruction(Liquibase.class,
                    (mock, ctx) -> doNothing().when(mock).update(any(Contexts.class), any(LabelExpression.class)))) {

                when(dataSource.getConnection()).thenReturn(connection);
                try (MockedStatic<DatabaseFactory> dbFactory = mockStatic(DatabaseFactory.class)) {
                    DatabaseFactory mockFactory = mock(DatabaseFactory.class);
                    dbFactory.when(DatabaseFactory::getInstance).thenReturn(mockFactory);
                    when(mockFactory.findCorrectDatabaseImplementation(any(JdbcConnection.class))).thenReturn(database);

                    assertDoesNotThrow(() -> service.updateSchema());
                }
            }
        }
    }
}
