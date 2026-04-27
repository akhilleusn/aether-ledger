package com.aetherledger.domain.enums;

public enum WebhookDeliveryStatus {
    PENDING,
    SUCCESS,
    FAILED,
    /** All retry attempts exhausted; requires manual intervention. */
    DEAD
}
