package com.aetherledger.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for all Spring Boot integration tests.
 *
 * <p>Starts a single PostgreSQL container for the entire JVM invocation via a static
 * initializer, so it stays alive across all test classes.  Testcontainers registers
 * its own JVM shutdown hook to stop the container when the process exits.
 *
 * <p>Using {@code @Testcontainers} + {@code @Container} would stop the container after
 * each top-level test class, causing connection failures for the next class.  The static
 * initializer approach is the correct pattern for sharing a container across multiple
 * test classes in a single test run.
 *
 * <p>Spring Boot's context-caching mechanism reuses the same application context across
 * all subclasses because they all receive the same dynamic property values from the
 * same container instance, making the full suite fast without sacrificing isolation.
 *
 * <p><strong>Test isolation</strong><br>
 * Call {@link #resetDatabase()} at the top of every {@code @BeforeEach} method.
 * It deletes all rows from every table in FK-safe order so each test starts with
 * a genuinely clean slate — regardless of what earlier test classes left behind.
 */
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("aetherledger_test")
                .withUsername("tc")
                .withPassword("tc");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Spring injects this through the @SpringBootTest subclass hierarchy.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Deletes every row from every table in FK-safe order.
     *
     * <p>Deletion order respects all foreign-key constraints declared in the
     * Flyway migrations — child tables are always cleared before their parents:
     * <ol>
     *   <li>{@code ledger_audit_chain} — no FK dependencies</li>
     *   <li>{@code idempotency_keys} — no FK dependencies</li>
     *   <li>{@code webhook_deliveries} — no FK to {@code webhook_subscriptions} (by design)</li>
     *   <li>{@code webhook_subscriptions} — no FK dependencies</li>
     *   <li>{@code outbox_events} — no FK dependencies</li>
     *   <li>{@code reconciliation_run_items} — FK → {@code reconciliation_runs},
     *       nullable FK → {@code ledger_transactions}</li>
     *   <li>{@code reconciliation_runs} — no FK dependencies after items are gone</li>
     *   <li>{@code integrity_snapshots} — no FK dependencies</li>
     *   <li>{@code ledger_entries} — FK → {@code accounts} and {@code ledger_transactions}</li>
     *   <li>{@code holds} — FK → {@code accounts}</li>
     *   <li>{@code ledger_transactions} — self-referential reversal FKs are safe for a
     *       full-table DELETE; nullable FK → {@code accounts}</li>
     *   <li>{@code accounts} — parent; deleted last</li>
     * </ol>
     */
    protected void resetDatabase() {
        // ── no FK dependencies ────────────────────────────────────────────────
        jdbcTemplate.execute("DELETE FROM ledger_audit_chain");
        jdbcTemplate.execute("DELETE FROM idempotency_keys");
        jdbcTemplate.execute("DELETE FROM webhook_deliveries");    // subscription_id is a plain UUID, no FK
        jdbcTemplate.execute("DELETE FROM webhook_subscriptions");
        jdbcTemplate.execute("DELETE FROM outbox_events");

        // ── reconciliation: items (child) before runs (parent) ────────────────
        jdbcTemplate.execute("DELETE FROM reconciliation_run_items"); // FK → reconciliation_runs + ledger_transactions
        jdbcTemplate.execute("DELETE FROM reconciliation_runs");

        // ── operational snapshots ─────────────────────────────────────────────
        jdbcTemplate.execute("DELETE FROM integrity_snapshots");

        // ── ledger layer: entries and holds before transactions before accounts ─
        jdbcTemplate.execute("DELETE FROM ledger_entries");   // FK → accounts, ledger_transactions
        jdbcTemplate.execute("DELETE FROM holds");            // FK → accounts
        jdbcTemplate.execute("DELETE FROM ledger_transactions"); // self-ref FKs OK on full-table DELETE
        jdbcTemplate.execute("DELETE FROM accounts");
    }
}
