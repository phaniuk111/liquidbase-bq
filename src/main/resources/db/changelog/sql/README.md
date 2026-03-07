# SQL-First Migration Pattern

This project enforces a SQL-first migration pattern for schema changes. Instead of writing XML-based changesets (`<createTable>`, `<addColumn>`), developers must write raw SQL scripts. 

These SQL scripts are then wrapped in an XML Liquibase changeset for version tracking, rollback enforcement, context filtering, and CI validation.

## Directory Structure
- `ddl/` - Contains raw `.sql` files for applying changes (CREATE, ALTER, DROP, etc.)
- `rollback/` - Contains raw `.sql` files used to reverse the corresponding DDL script.

## Step 1: Write SQL Scripts
Create your update and rollback scripts in their respective folders:

**`db/changelog/sql/ddl/008-add-user-email.sql`:**
```sql
ALTER TABLE user_profiles ADD COLUMN email STRING;
```

**`db/changelog/sql/rollback/008-drop-user-email.sql`:**
```sql
ALTER TABLE user_profiles DROP COLUMN email;
```

## Step 2: Wrap in XML
Create a new XML file (or add to an existing one) in `db/changelog/` that references the `.sql` files using `<sqlFile>`. 
**Remember to add `<preConditions>` for destructive operations like DROP.**

**`db/changelog/008-user-updates.xml`:**
```xml
<databaseChangeLog ...>
    <changeSet id="008-add-email" author="dev-name" context="dev,uat,prd" labels="JIRA-123">
        <!-- Path to the update SQL -->
        <sqlFile path="db/changelog/sql/ddl/008-add-user-email.sql" splitStatements="true" stripComments="true"/>
        
        <!-- Path to the rollback SQL -->
        <rollback>
            <sqlFile path="db/changelog/sql/rollback/008-drop-user-email.sql" splitStatements="true" stripComments="true"/>
        </rollback>
    </changeSet>
</databaseChangeLog>
```

## Step 3: Register File (if new XML)
Ensure your XML wrapper file is registered at the bottom of `db.changelog-master.xml`:
```xml
    <include file="db/changelog/008-user-updates.xml"/>
```
