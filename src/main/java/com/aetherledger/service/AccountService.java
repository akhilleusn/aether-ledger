package com.aetherledger.service;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import com.aetherledger.exception.AccountNotFoundException;
import com.aetherledger.exception.DuplicateAccountNameException;
import com.aetherledger.repository.AccountBalanceSummary;
import com.aetherledger.repository.AccountRepository;
import com.aetherledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Transactional
    public Account create(String name, AccountType type) {
        if (accountRepository.existsByName(name)) {
            throw new DuplicateAccountNameException(name);
        }
        Account account = accountRepository.save(Account.of(name, type));
        log.info("Account created: id={} name={} type={}", account.getId(), account.getName(), account.getType());
        return account;
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

    public record AccountWithBalance(Account account, BigDecimal currentBalance) {}
}
