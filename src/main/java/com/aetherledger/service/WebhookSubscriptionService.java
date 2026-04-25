package com.aetherledger.service;

import com.aetherledger.domain.entity.WebhookSubscription;
import com.aetherledger.domain.enums.OutboxEventType;
import com.aetherledger.exception.WebhookSubscriptionNotFoundException;
import com.aetherledger.repository.WebhookSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookSubscriptionService {

    private final WebhookSubscriptionRepository repository;

    @Transactional
    public WebhookSubscription create(String targetUrl, OutboxEventType eventType) {
        return repository.save(WebhookSubscription.create(targetUrl, eventType.name()));
    }

    @Transactional(readOnly = true)
    public List<WebhookSubscription> listActive() {
        return repository.findByActiveTrueOrderByCreatedAtAsc();
    }

    @Transactional
    public void deactivate(UUID id) {
        WebhookSubscription sub = repository.findById(id)
            .orElseThrow(() -> new WebhookSubscriptionNotFoundException(id));
        sub.deactivate();
        repository.save(sub);
    }
}
