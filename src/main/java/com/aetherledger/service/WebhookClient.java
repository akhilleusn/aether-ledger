package com.aetherledger.service;

import java.util.Map;

/**
 * Port interface for delivering a webhook payload to an external URL.
 *
 * <p>The default implementation ({@link LoggingWebhookClient}) logs the
 * attempt without making a real HTTP call.  Swap this bean for a real
 * HTTP adapter (e.g. using {@code RestClient} or {@code WebClient}) when
 * the project integrates a live delivery mechanism.
 *
 * <p>Implementations must never throw — they return a {@link SendResult}
 * so callers can record the outcome without catching exceptions.
 */
public interface WebhookClient {

    SendResult send(String targetUrl, String payload, Map<String, String> headers);

    record SendResult(boolean succeeded, String errorMessage) {
        static SendResult success()              { return new SendResult(true, null); }
        static SendResult failure(String error)  { return new SendResult(false, error); }
    }
}
