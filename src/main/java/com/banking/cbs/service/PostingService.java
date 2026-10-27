package com.banking.cbs.service;

import com.banking.cbs.dto.CashRequest;
import com.banking.cbs.dto.PostTransferRequest;
import com.banking.cbs.dto.PostingResponse;

public interface PostingService {

    PostingResponse postTransfer(PostTransferRequest request);

    PostingResponse postDeposit(CashRequest request);

    PostingResponse postWithdrawal(CashRequest request);
}