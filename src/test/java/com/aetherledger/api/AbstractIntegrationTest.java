package com.aetherledger.api;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for all Spring Boot integration tests.
 *
 * <p>Starts a single PostgreSQL container for the entire test run and registers
 * its connection details as dynamic Spring properties before any application
 * context is created.  Flyway then applies the real migration scripts against
 * this container, so tests execute against a schema that is byte-for-byte
 * identical to production.
 *
 * <p><strong>Container lifecycle</strong><br>
 * The container field is {@code static} and annotated with {@link Container},
 * so Testcontainers starts it once per JVM invocation and keeps it running
 * until the process exits.  Spring Boot's context-caching mechanism reuses
 * the same application context across all subclasses because they all receive
 * the same dynamic property values from the same container instance, making
 * the full test suite fast without sacrificing isolation between test methods.
 *
 * <p><strong>Test isolation</strong><br>
 * Each test class is responsible for cleaning up data in its {@code @BeforeEach}
 * method in foreign-key-safe order.  The container schema is never dropped
 * or recreated between test classes within a single run.
 */
@Testcontainers
abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("aetherledger_test")
                    .withUsername("tc")
                    .withPassword("tc");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
