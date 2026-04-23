package com.aetherledger.api;

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
 * Each test class is responsible for cleaning up data in its {@code @BeforeEach}
 * method in foreign-key-safe order.
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
}
