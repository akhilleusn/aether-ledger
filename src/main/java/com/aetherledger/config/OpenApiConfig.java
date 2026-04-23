package com.aetherledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aetherLedgerOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("AetherLedger API")
                .version("v1")
                .description("""
                    Production-grade double-entry financial ledger API.

                    **Core invariant:** every transaction debits one account and credits another \
                    for an equal and opposite amount, so the sum of all ledger entries is always zero.

                    **Append-only:** transactions and entries are never mutated or deleted after creation. \
                    Corrections are made via reversals, which create a new offsetting transaction.

                    **Idempotency:** `referenceId` is a caller-supplied unique key. \
                    Submitting the same `referenceId` twice returns 409 CONFLICT.
                    """)
                .contact(new Contact()
                    .name("AetherLedger Platform Team")
                    .email("platform@aetherledger.io")));
    }
}
