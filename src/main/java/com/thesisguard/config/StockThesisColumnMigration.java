package com.thesisguard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-off, idempotent schema fix run on startup.
 *
 * <p>{@code portfolio_role} became a descriptive free-text field with the flat thesis schema, but
 * databases created before that change still have it as {@code varchar(128)} — and Hibernate
 * {@code ddl-auto: update} never widens existing columns, so generation fails on save with
 * "value too long for type character varying(128)". This widens the column to TEXT in place.
 *
 * <p>Safe to run on every boot: it only issues the ALTER when the column is still a small bounded
 * varchar (a non-null {@code character_maximum_length}). It skips an already-migrated TEXT column
 * (null length on Postgres) and H2 in tests (where TEXT reports a huge length), and any failure is
 * logged without blocking startup.
 */
@Component
public class StockThesisColumnMigration {

    private static final Logger log = LoggerFactory.getLogger(StockThesisColumnMigration.class);

    private final JdbcTemplate jdbc;

    public StockThesisColumnMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void widenPortfolioRole() {
        try {
            Integer maxLength = jdbc.queryForObject(
                    "SELECT character_maximum_length FROM information_schema.columns "
                            + "WHERE LOWER(table_name) = 'stock_theses' AND LOWER(column_name) = 'portfolio_role'",
                    Integer.class);
            if (maxLength != null && maxLength <= 1024) {
                jdbc.execute("ALTER TABLE stock_theses ALTER COLUMN portfolio_role TYPE TEXT");
                log.info("[Migration] Widened stock_theses.portfolio_role (was varchar({})) to text", maxLength);
            }
        } catch (Exception ex) {
            log.warn("[Migration] Skipped portfolio_role widen check: {}", ex.getMessage());
        }
    }
}
