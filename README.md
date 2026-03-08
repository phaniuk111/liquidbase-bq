# Liquibase BigQuery Schema Manager

A **Spring Boot** application that uses [Liquibase](https://www.liquibase.org/) with the [liquibase-bigquery](https://github.com/liquibase/liquibase-bigquery) extension to **version-control Google BigQuery schema changes** — including full support for **nested STRUCT/RECORD columns**, **multi-environment deployment**, and **trunk-based CI/CD**.

---

## Features

| Operation | Method | Changelog |
|---|---|---|
| Create tables (flat) | Liquibase `createTable` | `001-create-tables.xml` |
| Create tables (nested STRUCT) | Raw SQL `CREATE TABLE` | `001-create-tables.xml` |
| Add columns (flat) | Liquibase `addColumn` | `002-add-columns.xml` |
| Add columns (STRUCT / ARRAY) | Raw SQL `ALTER TABLE ADD COLUMN` | `002-add-columns.xml` |
| Modify data types | Liquibase `modifyDataType` + raw SQL | `003-modify-datatypes.xml` |
| Drop columns | Raw SQL `ALTER TABLE DROP COLUMN` | `004-drop-columns.xml` |
| Rename columns | Raw SQL `ALTER TABLE RENAME COLUMN` | `005-rename-columns.xml` |
| Add field inside STRUCT | `CREATE OR REPLACE TABLE ... AS SELECT` | `006-nested-struct-operations.xml` |
| Drop field inside STRUCT | `CREATE OR REPLACE TABLE ... AS SELECT` | `006-nested-struct-operations.xml` |
| Modify field inside STRUCT | `CREATE OR REPLACE TABLE ... AS SELECT` | `006-nested-struct-operations.xml` |
| Add field in ARRAY\<STRUCT\> | `UNNEST` + rebuild array | `006-nested-struct-operations.xml` |
| Create / drop views | Liquibase `createView` / `dropView` | `007-views.xml` |

---

## Prerequisites

1. **Java 17 or higher** installed (enforced by Maven enforcer plugin)
2. **Google Cloud SDK** with `gcloud` CLI
3. **BigQuery dataset** in each target GCP project
4. **Simba BigQuery JDBC driver** — download from [Google Cloud](https://cloud.google.com/bigquery/docs/reference/odbc-jdbc-drivers)

### Install JDBC Driver Locally

```bash
./mvnw install:install-file \
  -Dfile=lib/GoogleBigQueryJDBC42.jar \
  -DgroupId=com.simba.googlebigquery.jdbc \
  -DartifactId=GoogleBigQueryJDBC42 \
  -Dversion=1.6.3.1004 \
  -Dpackaging=jar
```

### Authenticate

```bash
# Option 1: Application Default Credentials (recommended for dev)
gcloud auth application-default login

# Option 2: Service Account (for CI/CD)
# Set OAuthType=0 in environment properties
```

---

## Multi-Environment Setup

The project supports **multiple datasets per environment** using Spring profiles:

| Environment | Profile | Property File | GCP Project | Logging |
|---|---|---|---|---|
| **DEV 1** | `dev1` | `application-dev1.properties` | dev project | DEBUG |
| **DEV 2** | `dev2` | `application-dev2.properties` | dev project | DEBUG |
| **PRL1** | `prl1` | `application-prl1.properties` | prl1 project | INFO |
| **UAT 1** | `uat1` | `application-uat1.properties` | uat project | INFO |
| **UAT 2** | `uat2` | `application-uat2.properties` | uat project | INFO |
| **PRD** | `prd` | `application-prd.properties` | prd project | WARN |

### Configure Each Profile

Edit the property file for each profile:

```properties
# application-dev1.properties
spring.datasource.url=jdbc:bigquery://...;ProjectId=my-dev-project;DefaultDataset=dev_dataset_1;OAuthType=3

# application-uat2.properties
spring.datasource.url=jdbc:bigquery://...;ProjectId=my-uat-project;DefaultDataset=uat_dataset_2;OAuthType=3
```

### Environment Variables Override

```bash
export BQ_PROJECT_ID=my-project-id
export BQ_DATASET_ID=my_dataset
export SPRING_PROFILES_ACTIVE=uat1
```

---

## 🛑 PR Prerequisite: Developer Workflow

Before raising a Pull Request, **you must scaffold your migration and write your SQL**. You should NEVER create the `.xml` changelog wrappers manually.

1. **Scaffold the Migration (Required):**
   Use the `ChangeScaffoldCli` tool to auto-generate the required SQL files and XML wrapper in the correct directories, and automatically register it in the master changelog.
2. **Write the SQL:**
   Open the generated SQL files in `src/main/resources/db/changelog/sql/ddl/` and `rollback/`, and write your raw BigQuery SQL.

If your PR contains manually created XML files or is missing the generated SQL files, **the CI pipeline will fail the strict validation rules.**

---

## 5-Minute Quickstart (Using Executable JAR)
While you can use `./mvnw spring-boot:run`, compiling the application into an executable JAR is often faster and closer to how CI/CD pipelines run the tool.

### 0. Build the JAR
First, package the application (skipping tests for speed):
```bash
./mvnw clean package -DskipTests
```

### 1. Scaffold a New Migration
Run the CLI tool natively using Java 11+ source-file execution to auto-generate the DDL, Rollback SQL, and XML Wrapper files:
```bash
java src/main/java/com/example/liquidbasebq/tools/ChangeScaffoldCli.java \
  --name add-payment-table --domain orders --contexts dev,uat,prd --labels feature-123 --author email@example.com --register-master
```
*This command outputs the exact file paths it created so you can start editing immediately.*

### 2. Validate Locally
Once you have written your SQL in the generated files, run the strict structural validation using the JAR:
```bash
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev1 \
  --action=validate-strict
```
*If your changeset is missing a rollback block or preconditions, this will provide you the exact snippet to copy-paste to fix it.*

### 3. Preview SQL Deployment
Dry-run the generated SQL against a specific environment profile (e.g. `dev1`):
```bash
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev1 \
  --action=update-sql
```

---

## Architecture & Execution Flow

This is a **Spring Boot CLI application** (no web server). Spring Boot provides profile-based config, dependency injection, and fat JAR packaging.

### Project Structure

```
src/main/java/com/example/liquidbasebq/
├── LiquibaseBqApplication.java          ← @SpringBootApplication (entry point)
├── runner/
│   └── SchemaManagerRunner.java         ← @Component + CommandLineRunner (CLI logic)
├── service/
│   ├── BigQuerySchemaService.java       ← @Service (Liquibase operations)
│   └── BigQueryBackupService.java       ← @Service (snapshot/restore)
├── config/
│   └── LiquibaseConfig.java            ← @Configuration (datasource setup)
├── validation/
│   └── ValidationConfig.java           ← @Configuration (strict validation)
└── tools/
    └── ChangeScaffoldCli.java           ← Standalone CLI (not Spring-managed)
```

### Execution Flow

When you run `java -jar app.jar --spring.profiles.active=dev1 --action=update`:

```
──── STARTUP ──────────────────────────────────────────────────────

① LiquibaseBqApplication.java
   └── main(args) → SpringApplication.run()

──── CONFIG LOADING ───────────────────────────────────────────────

② application.properties                    ← default config (loaded first)
③ application-dev1.properties               ← dev1 overrides (datasource URL, project ID)

──── BEAN CREATION (dependency order) ─────────────────────────────

④ LiquibaseConfig.java                     ← creates DataSource + Liquibase instance
⑤ ValidationConfig.java                    ← standalone, no dependencies
⑥ BigQuerySchemaService.java               ← needs LiquibaseConfig (④)
⑦ BigQueryBackupService.java               ← needs LiquibaseConfig (④)
⑧ SchemaManagerRunner.java                 ← needs ⑤ + ⑥ + ⑦ (all ready now)

──── COMMANDLINERUNNER EXECUTION ──────────────────────────────────

⑨ SchemaManagerRunner.run(args)
   └── parses --action=update, --tag=v1
   └── switch("update")
   └── calls schemaService.updateSchema()

⑩ BigQuerySchemaService.updateSchema()
   └── gets Liquibase instance from LiquibaseConfig
   └── calls liquibase.update()

──── LIQUIBASE ENGINE (inside updateSchema) ───────────────────────

⑪ db.changelog-master.xml                  ← Liquibase reads this ("table of contents")
⑫ 001-create-tables.xml → sql/ddl/...      ← first <include> entry
⑬ 002-add-columns.xml → sql/ddl/...        ← second <include>
   ... (each <include> in master.xml order)

──── FOR EACH CHANGESET ───────────────────────────────────────────

   Liquibase checks DATABASECHANGELOG in BQ:
     → Already applied? SKIP
     → New? Execute SQL → insert tracking row

──── SHUTDOWN ─────────────────────────────────────────────────────

⑲ SchemaManagerRunner.run() returns
   └── Spring context closes → JVM exits
```

### How Spring Wires Everything

- `@SpringBootApplication` triggers `@ComponentScan` — scans all sub-packages for `@Component`, `@Service`, `@Configuration`
- Spring matches constructor parameter **types** to beans (one bean per type)
- `CommandLineRunner` interface tells Spring to call `run(args)` **after** all beans are created
- `ChangeScaffoldCli` has no Spring annotation — it runs standalone via `exec:java`

### Liquibase Tracking Tables

Liquibase auto-creates two tables on the first `update` run:

| Table | Purpose |
|---|---|
| `DATABASECHANGELOG` | Audit trail — one row per applied changeset (ID, author, checksum, date, tag) |
| `DATABASECHANGELOGLOCK` | Advisory lock — prevents concurrent migrations |

---

## All Supported CLI Actions


| Action Flag | Description |
|---|---|
| `--action=validate-strict` | Enforce strict governance rules (rollback, context, etc) |
| `--action=update` | Apply pending changesets directly to the database |
| `--action=update-sql` | Preview SQL (dry-run) without applying |
| `--action=status` | Show pending vs applied changesets |
| `--action=history` | Full deployment history with tags |
| `--action=tag --tag=TAG` | Tag current database state |
| `--action=rollback --tag=TAG` | Rollback to a named tag |
| `--action=rollback-count --count=N` | Rollback last N changesets |

```bash
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=prd --action=backup                 # Snapshot backup
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=prd --action=export-gcs --tag=v3    # GCS long-term backup
```

### Running on any OS (Git Bash / CMD / Linux)

Since the application is packaged as a JAR, all commands work identically across any OS:

```bash
# Check status (dev1)
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev1 --action=status

# Apply changes (uat1)
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=uat1 --action=update

# Preview SQL (prd)
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=prd --action=update-sql

# Validate changelog XML
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev1 --action=validate

# Snapshot backup (prd)
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=prd --action=backup

# Rollback to tag (uat1)
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=uat1 --action=rollback --tag=baseline-v1

# Deployment history
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev1 --action=history

# GCS export (long-term backup)
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar --spring.profiles.active=prd --action=export-gcs --tag=pre_v3 --format=PARQUET
```

> **Note**: Just ensure you have run `./mvnw clean package -DskipTests` to build the JAR first.

---

## Rollback

### Rollback to a Tag

Every changelog file creates a tag after its operations. Available tags:

| Tag | After |
|---|---|
| `baseline-v1` | Initial table creation |
| `columns-added-v2` | Column additions |
| `datatypes-modified-v3` | Datatype modifications |
| `columns-dropped-v4` | Column drops |
| `columns-renamed-v5` | Column renames |
| `nested-struct-ops-v6` | Nested STRUCT operations |
| `views-created-v7` | View creation |

CI/CD also creates `pre-deploy-{version}` and `post-deploy-{version}` tags on each deployment.

```bash
# Rollback UAT1 to baseline
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=uat1 \
  --action=rollback --tag=baseline-v1

# Preview rollback SQL on dev2
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev2 \
  --action=rollback-sql --tag=columns-added-v2

# Rollback last 3 changesets on uat2
java -jar target/liquidbase-bq-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=uat2 \
  --action=rollback-count --count=3
```

---

## CI/CD — GitHub Actions

### Trunk-Based Deployment Flow

```
PR → feature.yaml (validate + dry-run SQL)
     ↓ merge
main → main.yaml:
  Build → DEV (dev1 + dev2) → PRL1 → UAT (uat1 + uat2) ⏸ → PRD ⏸
                ↑ parallel                    ↑ parallel
```

### `feature.yaml` — PR Validation

Triggered on **pull requests to main**:
1. Compile project
2. Validate changelog XML
3. Generate dry-run SQL against DEV
4. Post generated SQL as PR comment for review

### `main.yaml` — Sequential Deployment

Triggered on **push to main**:

| Step | Datasets | Approval | Strategy |
|---|---|---|---|
| 1. Build & Validate | — | Auto | — |
| 2. **DEV** | dev1, dev2 | Auto | Parallel matrix |
| 3. **PRL1** | prl1 | Auto | Single |
| 4. **UAT** | uat1, uat2 | ⏸ Manual | Parallel matrix |
| 5. **PRD** | prd | ⏸ Manual | Single |

### Manual Dispatch

Trigger from GitHub Actions UI with:
- **Target profile** — `dev1`, `dev2`, `prl1`, `uat1`, `uat2`, `prd`
- **Action** — update, update-sql, status, rollback, history, validate
- **Rollback tag** — specify tag for rollback actions

### Required GitHub Setup

1. **Create environments**: `dev`, `prl1`, `uat`, `prd` in repo Settings → Environments
2. **Add protection rules**: Require approvers for `uat` and `prd`
3. **Add secrets per environment**:

| Secret | Description |
|---|---|
| `WIF_PROVIDER` | Workload Identity Federation provider |
| `SA_EMAIL` | Service account email |
| `BQ_PROJECT_ID` | GCP project ID |
| `BQ_DATASET_ID` | BigQuery dataset ID |

---

## Project Structure

```
liquidbase-bq/
├── .github/workflows/
│   ├── feature.yaml                           # PR validation + dry-run
│   └── main.yaml                              # Trunk deploy with matrix
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/example/liquidbasebq/
    │   ├── LiquibaseBqApplication.java        # Spring Boot entry point
    │   ├── config/
    │   │   └── LiquibaseConfig.java           # Profile-aware Liquibase config
    │   ├── runner/
    │   │   └── SchemaManagerRunner.java        # CLI runner (10 actions)
    │   └── service/
    │       └── BigQuerySchemaService.java      # Programmatic Liquibase API
    └── resources/
        ├── application.properties              # Base config (default: dev1)
        ├── application-dev1.properties         # DEV dataset 1
        ├── application-dev2.properties         # DEV dataset 2
        ├── application-prl1.properties         # PRL1
        ├── application-uat1.properties         # UAT dataset 1
        ├── application-uat2.properties         # UAT dataset 2
        ├── application-prd.properties          # PRD
        ├── env/                                # Source-of-truth env configs
        │   ├── dev/dev1/ dev2/
        │   ├── prl1/
        │   ├── uat/uat1/ uat2/
        │   └── prd/
        └── db/changelog/
            ├── db.changelog-master.xml         # Master changelog
            ├── 001-create-tables.xml           # Tables (flat + nested)
            ├── 002-add-columns.xml             # Add columns
            ├── 003-modify-datatypes.xml        # Modify data types
            ├── 004-drop-columns.xml            # Drop columns
            ├── 005-rename-columns.xml          # Rename columns
            ├── 006-nested-struct-operations.xml # Nested STRUCT workarounds
            └── 007-views.xml                   # Create/drop views
```

---

## License

MIT

