package com.thesisguard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-off, idempotent schema fix run on startup.
 *
 * <p>Deleting a stock relies on DB-level {@code ON DELETE CASCADE} foreign keys (Stock maps no child
 * collections, so the delete is a plain {@code DELETE FROM stocks}). Three child FKs were created
 * without cascade, so the delete failed with a constraint violation on {@code news_analysis_items}.
 * The {@code @OnDelete} annotations fix fresh databases, but Hibernate {@code ddl-auto: update} never
 * alters existing FKs — so on databases created before the fix, recreate those FKs WITH cascade here.
 *
 * <p>Idempotent: each FK is only touched while its {@code delete_rule} is not already {@code CASCADE},
 * so a second boot is a no-op. Fail-safe per FK: a failure is logged without blocking startup, and on
 * H2 (tests) the lookup simply matches nothing.
 */
@Component
public class ForeignKeyCascadeMigration {

    private static final Logger log = LoggerFactory.getLogger(ForeignKeyCascadeMigration.class);

    private final JdbcTemplate jdbc;

    public ForeignKeyCascadeMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureCascades() {
        ensureCascade("news_analysis_items", "daily_news_review_id", "daily_news_reviews", "fk_nai_review_cascade");
        ensureCascade("news_analysis_items", "news_item_id", "news_items", "fk_nai_news_cascade");
        ensureCascade("alerts", "daily_news_review_id", "daily_news_reviews", "fk_alert_review_cascade");
    }

    private void ensureCascade(String childTable, String childColumn, String parentTable, String newName) {
        try {
            List<String> names = jdbc.queryForList(
                    "SELECT tc.constraint_name FROM information_schema.table_constraints tc "
                            + "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name "
                            + "JOIN information_schema.referential_constraints rc ON tc.constraint_name = rc.constraint_name "
                            + "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ? AND kcu.column_name = ? "
                            + "AND rc.delete_rule <> 'CASCADE'",
                    String.class, childTable, childColumn);
            for (String name : names) {
                jdbc.execute("ALTER TABLE " + childTable + " DROP CONSTRAINT \"" + name + "\"");
                jdbc.execute("ALTER TABLE " + childTable + " ADD CONSTRAINT " + newName
                        + " FOREIGN KEY (" + childColumn + ") REFERENCES " + parentTable + "(id) ON DELETE CASCADE");
                log.info("[Migration] Recreated FK {}.{} -> {} with ON DELETE CASCADE (was {})",
                        childTable, childColumn, parentTable, name);
            }
        } catch (Exception ex) {
            log.warn("[Migration] Skipped cascade check for {}.{}: {}", childTable, childColumn, ex.getMessage());
        }
    }
}
