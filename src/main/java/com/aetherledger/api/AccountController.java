package com.aetherledger.api;

import com.aetherledger.api.dto.AccountCreatedResponse;
import com.aetherledger.api.dto.AccountResponse;
import com.aetherledger.api.dto.CreateAccountRequest;
import com.aetherledger.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountCreatedResponse create(@RequestBody @Valid CreateAccountRequest request) {
        log.debug("Creating account: name={} type={}", request.name(), request.type());
        return AccountCreatedResponse.from(accountService.create(request.name(), request.type()));
    }

    @GetMapping("/{id}")
    public AccountResponse getById(@PathVariable UUID id) {
        AccountService.AccountWithBalance awb = accountService.getById(id);
        return AccountResponse.from(awb.account(), awb.currentBalance());
    }

    @GetMapping
    public List<AccountResponse> list() {
        return accountService.list().stream()
            .map(awb -> AccountResponse.from(awb.account(), awb.currentBalance()))
            .toList();
    }
}
