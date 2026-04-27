package com.aetherledger.exception;

import java.util.UUID;

public class WebhookDeliveryNotFoundException extends LedgerException {
    public WebhookDeliveryNotFoundException(UUID id) {
        super("Webhook delivery not found: id=" + id);
    }
}
