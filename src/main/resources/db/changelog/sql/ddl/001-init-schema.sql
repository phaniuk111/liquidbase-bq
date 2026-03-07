-- db/changelog/sql/ddl/001-init-schema.sql
-- Consolidated initialization script for the application schema
-- Represents the final state after applying all tutorial migrations (001-007)

-- ==========================================
-- 1. CUSTOMERS TABLE
-- ==========================================
CREATE TABLE IF NOT EXISTS customers (
    customer_id  STRING NOT NULL,
    first_name   STRING,
    last_name    STRING,
    email        STRING,
    created_at   TIMESTAMP,
    active_flag  BOOL,        -- Originally is_active, renamed in 005
    loyalty_tier STRING,      -- Added in 002
    total_spent  NUMERIC      -- Added in 002
    -- phone_number STRING added in 002, dropped in 004
);

-- ==========================================
-- 2. ORDERS TABLE
-- ==========================================
CREATE TABLE IF NOT EXISTS orders (
    order_id      STRING NOT NULL,
    customer_id   STRING,
    amount        NUMERIC,     -- Created as FLOAT64, changed to NUMERIC in 003
    currency      STRING,
    order_status  STRING,      -- originally status, renamed in 005
    order_date    DATE,
    created_at    TIMESTAMP,
    discount      NUMERIC,     -- Added as FLOAT64 in 002, changed to NUMERIC in 003
    shipping_cost NUMERIC,     -- Added in 002
    -- notes STRING added in 002, dropped in 004
    order_items   ARRAY<STRUCT<
        product_id   STRING,
        product_name STRING,
        quantity     INT64,
        unit_price   NUMERIC
    >>                       -- Added in 006
);

-- ==========================================
-- 3. PRODUCTS TABLE
-- ==========================================
CREATE TABLE IF NOT EXISTS products (
    product_id   STRING NOT NULL,
    product_name STRING,       -- Originally name, renamed in 005
    description  STRING,
    price        BIGNUMERIC,   -- Created as NUMERIC, widened to BIGNUMERIC in 003
    category     STRING,
    created_at   TIMESTAMP,
    tags         ARRAY<STRING> -- Added in 002
);

-- ==========================================
-- 4. EVENTS TABLE (Nested Structs)
-- ==========================================
-- Reconstructed based on modifications in 006 and column drops in 004
CREATE TABLE IF NOT EXISTS events (
    event_id     STRING NOT NULL,
    event_type   STRING,
    user_id      STRING,
    event_time   TIMESTAMP,
    -- audit_info STRUCT added in 002, dropped in 004
    
    -- location STRUCT: originally (city, state, country)
    -- 006-1: added zip_code
    -- 006-3: dropped state
    
    location STRUCT<
        city     STRING,
        country  STRING,
        zip_code STRING
    >,

    -- metadata ARRAY of STRUCT
    metadata ARRAY<STRUCT<
        key   STRING,
        value STRING
    >>,

    -- device STRUCT WITH sub-STRUCT browser
    -- 006-2: added user_agent to browser struct
    device STRUCT<
        os      STRING,
        version STRING,
        browser STRUCT<
            name       STRING,
            version    STRING,
            user_agent STRING
        >
    >
);

-- ==========================================
-- 5. USER_PROFILES TABLE (Deeply Nested Array/Structs)
-- ==========================================
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id    STRING NOT NULL,
    username   STRING,
    email      STRING,
    created_at TIMESTAMP,

    -- addresses ARRAY of STRUCT
    -- 006-5: added apartment field
    addresses ARRAY<STRUCT<
        address_type STRING,
        street       STRING,
        apartment    STRING,
        city         STRING,
        state        STRING,
        zip_code     STRING,
        country      STRING,
        is_primary   BOOL
    >>,

    -- preferences STRUCT
    -- 006-4: added frequency STRING field to notification struct
    preferences STRUCT<
        language     STRING,
        timezone     STRING,
        notification STRUCT<
            email_enabled BOOL,
            sms_enabled   BOOL,
            push_enabled  BOOL,
            frequency     STRING
        >
    >
);

-- ==========================================
-- V1. VIEWS
-- ==========================================
-- Derived from 007-views.xml

CREATE OR REPLACE VIEW active_customers_view AS
SELECT
    customer_id,
    first_name,
    last_name,
    email,
    loyalty_tier,
    total_spent,
    created_at
FROM customers
WHERE active_flag = TRUE;

CREATE OR REPLACE VIEW order_summary_view AS
SELECT
    c.customer_id,
    c.first_name,
    c.last_name,
    COUNT(o.order_id)     AS total_orders,
    SUM(o.amount)         AS total_amount,
    MAX(o.order_date)     AS last_order_date
FROM customers c
LEFT JOIN orders o ON c.customer_id = o.customer_id
GROUP BY c.customer_id, c.first_name, c.last_name;

CREATE OR REPLACE VIEW event_locations_view AS
SELECT
    event_id,
    event_type,
    user_id,
    event_time,
    location.city     AS city,
    location.country  AS country,
    location.zip_code AS zip_code
FROM events;

CREATE OR REPLACE VIEW event_metadata_view AS
SELECT
    e.event_id,
    e.event_type,
    e.user_id,
    m.key   AS metadata_key,
    m.value AS metadata_value
FROM events e,
UNNEST(e.metadata) AS m;

CREATE OR REPLACE VIEW user_addresses_view AS
SELECT
    u.user_id,
    u.username,
    addr.address_type,
    addr.street,
    addr.apartment,
    addr.city,
    addr.state,
    addr.zip_code,
    addr.country,
    addr.is_primary
FROM user_profiles u,
UNNEST(u.addresses) AS addr;
