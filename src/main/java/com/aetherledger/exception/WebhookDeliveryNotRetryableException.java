package com.aetherledger.exception;

import com.aetherledger.domain.enums.WebhookDeliveryStatus;
import java.util.UUID;

public class WebhookDeliveryNotRetryableException extends LedgerException {
    public WebhookDeliveryNotRetryableException(UUID id, WebhookDeliveryStatus status) {
        super("Webhook delivery " + id + " cannot be retried: status is " + status);
    }
}
