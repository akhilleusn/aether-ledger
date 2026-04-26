package com.aetherledger.service;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides the singleton "Settlement Clearing" system account that acts as the
 * counterparty when a hold is captured and funds move in the ledger.
 *
 * <p>Runs in its own {@code REQUIRES_NEW} transaction so the account row is
 * committed before the outer capture transaction uses it. If two concurrent
 * captures race to create the account the loser catches the unique-name
 * constraint violation and falls back to a plain SELECT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClearingAccountProvider {

    static final String CLEARING_ACCOUNT_NAME = "Settlement Clearing";

    private final AccountRepository accountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Account resolve() {
        return accountRepository.findByName(CLEARING_ACCOUNT_NAME)
            .orElseGet(this::create);
    }

    private Account create() {
        try {
            Account account = accountRepository.save(
                Account.of(CLEARING_ACCOUNT_NAME, AccountType.SYSTEM));
            log.info("Clearing account created: id={}", account.getId());
            return account;
        } catch (DataIntegrityViolationException ex) {
            log.debug("Clearing account created concurrently; falling back to SELECT");
            return accountRepository.findByName(CLEARING_ACCOUNT_NAME)
                .orElseThrow(() -> new IllegalStateException(
                    "Clearing account not found after concurrent creation", ex));
        }
    }
}
