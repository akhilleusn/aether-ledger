package com.aetherledger.service;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.entity.LedgerEntry;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.exception.AccountNotFoundException;
import com.aetherledger.exception.DuplicateAccountNameException;
import com.aetherledger.repository.AccountBalanceSummary;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Creates a new account.
     *
     * <p>The name is stripped of leading/trailing whitespace before persistence.
     * Uniqueness is checked optimistically with a pre-flight query, then
     * enforced at the database level by {@code uq_account_name}.  The
     * {@link DataIntegrityViolationException} catch guards the race window
     * between the existence check and the INSERT.
     */
    @Transactional
    public Account create(String name, AccountType type) {
        String trimmedName = name.strip();
        if (accountRepository.existsByName(trimmedName)) {
            throw new DuplicateAccountNameException(trimmedName);
        }
        try {
            Account account = accountRepository.save(Account.of(trimmedName, type));
            log.info("Account created: id={} name='{}' type={}", account.getId(), account.getName(), account.getType());
            return account;
        } catch (DataIntegrityViolationException ex) {
            // Guards the race window between the existence check and the INSERT.
            // The uq_account_name constraint is the final enforcement barrier.
            log.warn("Duplicate account name detected at INSERT: name='{}'", trimmedName);
            throw new DuplicateAccountNameException(trimmedName);
        }
    }

    /**
     * Returns an account together with its current running balance derived
     * from the ledger.  The balance is always authoritative — it is never
     * read from a cached column on the {@link Account} entity.
     */
    @Transactional(readOnly = true)
    public AccountWithBalance getById(UUID id) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new AccountNotFoundException(id, "id"));
        BigDecimal balance = ledgerEntryRepository.sumAmountByAccountId(id);
        return new AccountWithBalance(account, balance);
    }

    /**
     * Returns all accounts with their current running balances.
     *
     * <p>Balances are loaded in a single GROUP BY query to avoid N+1
     * round-trips.  Accounts that have never had a ledger entry carry a
     * balance of {@link BigDecimal#ZERO}.
     */
    @Transactional(readOnly = true)
    public List<AccountWithBalance> list() {
        List<Account> accounts = accountRepository.findAll();

        Map<UUID, BigDecimal> balanceByAccountId = ledgerEntryRepository
            .sumAmountGroupByAccountId()
            .stream()
            .collect(Collectors.toMap(AccountBalanceSummary::accountId, AccountBalanceSummary::balance));

        return accounts.stream()
            .map(a -> new AccountWithBalance(a, balanceByAccountId.getOrDefault(a.getId(), BigDecimal.ZERO)))
            .toList();
    }

    /**
     * Returns all ledger entries for an account ordered newest first.
     *
     * <p>The parent {@link com.aetherledger.domain.entity.LedgerTransaction} is
     * JOIN FETCH-ed so callers can access {@code referenceId} without triggering
     * additional queries.
     *
     * @throws AccountNotFoundException if no account with the given id exists
     */
    @Transactional(readOnly = true)
    public List<LedgerEntry> getLedgerHistory(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId, "id");
        }
        return ledgerEntryRepository.findByAccountIdWithTransaction(accountId);
    }

    public record AccountWithBalance(Account account, BigDecimal currentBalance) {}
}
