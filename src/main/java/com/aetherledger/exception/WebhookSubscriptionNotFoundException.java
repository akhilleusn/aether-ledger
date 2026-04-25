package com.aetherledger.exception;

import java.util.UUID;

public class WebhookSubscriptionNotFoundException extends LedgerException {

    public WebhookSubscriptionNotFoundException(UUID id) {
        super("Webhook subscription not found: id=" + id);
    }
}
