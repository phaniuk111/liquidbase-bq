# Contributing — Schema Change Guide

How to safely add, test, and deploy BigQuery schema changes.

---

## Developer Contract

This project enforces a strict separation of concerns:

| Responsibility | Owner |
|---|---|
| Forward SQL (`sql/ddl/{domain}/`) | **Developer** |
| Rollback SQL (`sql/rollback/{domain}/`) | **Developer** |
| XML wrapper (`<sqlFile>`, `<rollback>`, context, labels, author) | **`ChangeScaffoldCli`** (automation) |
| Master include registration (`db.changelog-master.xml`) | **`ChangeScaffoldCli`** (automation) |
| Strict validation + PR fail gates | **CI** (`feature.yaml`) |

**You never hand-write XML.** The scaffold CLI generates all wrapper metadata. CI blocks your PR if any rule is violated.

---

## 🔒 Golden Rules

1. **Never modify an existing changeset** that has been deployed. Create a new one.
2. **Always preview SQL** before applying: `./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev1 -Dspring-boot.run.arguments="--action=update-sql"`
3. **Always tag** before destructive operations.
4. **Never drop columns in production** without a backup/migration plan.
5. **Run all tests** before pushing: `./mvnw test -B`

---

## Step-by-Step: Adding a Schema Change

### 1. Create a Feature Branch

```bash
git checkout -b feature/add-payment-table
```

### 2. Scaffold via CLI

Run the scaffold tool to generate all required files automatically:

```bash
./mvnw -q -DskipTests exec:java -Dexec.mainClass=com.example.liquidbasebq.tools.ChangeScaffoldCli \
  -Dexec.args="--name add-payment-table --domain orders --contexts dev,uat,prd --labels JIRA-123 --author you@example.com --register-master"
```

This single command creates:
- 📝 `sql/ddl/orders/{timestamp}-add-payment-table.sql` — your forward SQL (placeholder)
- ⏪ `sql/rollback/orders/{timestamp}-add-payment-table_rollback.sql` — your rollback SQL (placeholder)
- 🏷️ `{timestamp}-add-payment-table.xml` — the XML wrapper with `<sqlFile>`, `<rollback>`, context, labels, and author pre-filled
- 🔗 `db.changelog-master.xml` — include registered automatically (via `--register-master`)

### 3. Write Your SQL

Edit **only** the two generated `.sql` files:

**Forward DDL** (`sql/ddl/orders/{timestamp}-add-payment-table.sql`):
```sql
CREATE TABLE payment_methods (
    id STRING,
    name STRING,
    provider STRUCT<code STRING, display_name STRING>
);
```

**Rollback** (`sql/rollback/orders/{timestamp}-add-payment-table_rollback.sql`):
```sql
DROP TABLE IF EXISTS payment_methods;
```

> **Important**: If your change is destructive (DROP, TRUNCATE, DELETE), uncomment the `<preConditions>` block in the generated XML wrapper.

### 4. Validate Locally

```bash
# Strict governance validation (rollback, context, labels, preconditions)
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--action=validate-strict"

# Preview SQL against dev1
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev1 -Dspring-boot.run.arguments="--action=update-sql"
```

If `validate-strict` fails, it provides the exact remediation snippet to copy-paste.

### 5. Push and Create PR

```bash
git add .
git commit -m "feat: add payment_methods table"
git push origin feature/add-payment-table
```

### What CI Does on Your PR

Your PR automatically triggers the following gates:

1. ✅ Compile
2. ✅ Unit tests
3. ✅ Changelog XML well-formedness
4. ✅ **Strict governance validation** (`validate-strict`)
   - ❌ Missing `<rollback>` → PR blocked
   - ❌ Missing `context` attribute → PR blocked
   - ❌ Missing `labels` attribute → PR blocked
   - ❌ Destructive SQL without `<preConditions>` → PR blocked
   - ❌ Unreadable `sqlFile` reference → PR blocked
5. ✅ Dry-run SQL posted as PR comment with risk flags (DROP, TRUNCATE, DELETE)

**Your PR cannot merge if `validate-strict` fails.**

### 6. Review & Merge

- Review the generated SQL diff in the PR comment
- Get approval from reviewers
- Merge to `main` → auto-deploys: dev → prl1 → uat (approval) → prd (approval)

---

## Data Loss Protection

### Preconditions — Prevent Accidental Damage

Always add preconditions to destructive changesets (uncomment the block in the generated XML):

```xml
<!-- Only run if table EXISTS (prevents error on clean DB) -->
<preConditions onFail="MARK_RAN">
    <tableExists tableName="my_table"/>
</preConditions>

<!-- Only run if column EXISTS -->
<preConditions onFail="MARK_RAN">
    <columnExists tableName="my_table" columnName="old_column"/>
</preConditions>

<!-- Only run if table does NOT exist (safe create) -->
<preConditions onFail="MARK_RAN">
    <not><tableExists tableName="new_table"/></not>
</preConditions>
```

### Backup Before Drop — The Safe Pattern

For columns that contain data, **back up before dropping**:

```xml
<!-- Step 1: Backup the data -->
<changeSet id="backup-before-drop" author="dev">
    <sql>
        CREATE TABLE IF NOT EXISTS my_dataset.orders_backup AS
        SELECT * FROM my_dataset.orders
    </sql>
    <rollback>
        <sql>DROP TABLE IF EXISTS my_dataset.orders_backup</sql>
    </rollback>
</changeSet>

<!-- Step 2: Drop the column -->
<changeSet id="drop-column-safely" author="dev">
    <preConditions onFail="MARK_RAN">
        <columnExists tableName="orders" columnName="legacy_field"/>
    </preConditions>
    <sql>ALTER TABLE orders DROP COLUMN legacy_field</sql>
    <rollback>
        <sql>
            -- Restore from backup
            CREATE OR REPLACE TABLE my_dataset.orders AS
            SELECT * FROM my_dataset.orders_backup
        </sql>
    </rollback>
</changeSet>
```

### Rename Instead of Drop

When possible, **rename** columns instead of dropping:

```xml
<changeSet id="deprecate-old-column" author="dev">
    <sql>ALTER TABLE orders RENAME COLUMN old_name TO _deprecated_old_name</sql>
    <rollback>
        <sql>ALTER TABLE orders RENAME COLUMN _deprecated_old_name TO old_name</sql>
    </rollback>
</changeSet>

<!-- Drop the deprecated column in a LATER release, after confirming no impact -->
```

### Never Modify Deployed Changesets

| ❌ Don't | ✅ Do |
|---|---|
| Edit an existing changeset | Create a new changeset |
| Change a changeset ID | Use a new unique ID |
| Alter SQL in a deployed changeset | Add a new migration |

If you must fix a deployed changeset, use:
```bash
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev1 -Dspring-boot.run.arguments="--action=clear-checksums"
```
Then redeploy. **Only do this in dev — never in uat/prd.**

---

## Changeset Checklist

Before submitting a PR, verify:

- [ ] Ran `ChangeScaffoldCli` with `--register-master` (do not hand-write XML)
- [ ] Edited the forward SQL in `sql/ddl/{domain}/`
- [ ] Edited the rollback SQL in `sql/rollback/{domain}/`
- [ ] Uncommented `<preConditions>` if destructive (DROP/TRUNCATE/DELETE)
- [ ] `./mvnw test -B` passes
- [ ] `./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--action=validate-strict"` passes
- [ ] `./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev1 -Dspring-boot.run.arguments="--action=update-sql"` reviewed
- [ ] No sensitive data in SQL files

---

## Common Operations Cheat Sheet

```bash
# ─── Scaffold ───
./mvnw -q -DskipTests exec:java -Dexec.mainClass=com.example.liquidbasebq.tools.ChangeScaffoldCli \
  -Dexec.args="--name my-change --domain my-domain --contexts dev,uat,prd --labels JIRA-XXX --register-master"

# ─── Local Development ───
./mvnw test -B                   # Run unit tests
# Preview SQL (dev1)
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev1 -Dspring-boot.run.arguments="--action=update-sql"
# Check what's pending on UAT1
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=uat1 -Dspring-boot.run.arguments="--action=status"
# See what was deployed to PRD
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=prd -Dspring-boot.run.arguments="--action=history"

# ─── Apply Changes ───
# Apply to dev1
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev1 -Dspring-boot.run.arguments="--action=update"
# Tag before risky change
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev1 -Dspring-boot.run.arguments="--action=tag --tag=pre-my-change"

# ─── Rollback ───
# Rollback dev1
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev1 -Dspring-boot.run.arguments="--action=rollback --tag=pre-my-change"
# Preview rollback SQL
./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=uat1 -Dspring-boot.run.arguments="--action=rollback-sql --tag=v1"
```
