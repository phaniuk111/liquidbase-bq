-- db/changelog/sql/rollback/001-init-schema.sql
-- Drops all tables and views created by the initial schema

DROP VIEW IF EXISTS active_customers_view;
DROP VIEW IF EXISTS order_summary_view;
DROP VIEW IF EXISTS event_locations_view;
DROP VIEW IF EXISTS event_metadata_view;
DROP VIEW IF EXISTS user_addresses_view;

DROP TABLE IF EXISTS customers;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS events;
DROP TABLE IF EXISTS user_profiles;
