package com.banking.cbs.controller;

import com.banking.cbs.dto.BalanceResponse;
import com.banking.cbs.dto.CashRequest;
import com.banking.cbs.dto.CreateLedgerAccountRequest;
import com.banking.cbs.dto.PostTransferRequest;
import com.banking.cbs.dto.PostingResponse;
import com.banking.cbs.service.LedgerAccountService;
import com.banking.cbs.service.PostingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/cbs")
public class InternalCbsController {

    private final LedgerAccountService accountService;
    private final PostingService postingService;

    public InternalCbsController(LedgerAccountService accountService, PostingService postingService) {
        this.accountService = accountService;
        this.postingService = postingService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<BalanceResponse> create(@Valid @RequestBody CreateLedgerAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(request));
    }

    @GetMapping("/accounts/{accountNumber}/balance")
    public ResponseEntity<BalanceResponse> balance(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.getBalance(accountNumber));
    }

    @GetMapping("/balances")
    public ResponseEntity<List<BalanceResponse>> balances(@RequestParam List<String> accountNumbers) {
        return ResponseEntity.ok(accountService.getBalances(accountNumbers));
    }

    @PostMapping("/transfers")
    public ResponseEntity<PostingResponse> postTransfer(@Valid @RequestBody PostTransferRequest request) {
        return ResponseEntity.ok(postingService.postTransfer(request));
    }

    @PostMapping("/deposits")
    public ResponseEntity<PostingResponse> deposit(@Valid @RequestBody CashRequest request) {
        return ResponseEntity.ok(postingService.postDeposit(request));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<PostingResponse> withdraw(@Valid @RequestBody CashRequest request) {
        return ResponseEntity.ok(postingService.postWithdrawal(request));
    }
}