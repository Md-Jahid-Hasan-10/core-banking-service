package com.banking.cbs.service;

import com.banking.cbs.dto.BalanceResponse;
import com.banking.cbs.dto.CreateLedgerAccountRequest;

import java.util.List;

public interface LedgerAccountService {

    BalanceResponse create(CreateLedgerAccountRequest request);

    BalanceResponse getBalance(String accountNumber);

    List<BalanceResponse> getBalances(List<String> accountNumbers);
}