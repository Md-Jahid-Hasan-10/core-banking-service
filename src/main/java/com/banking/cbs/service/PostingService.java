package com.banking.cbs.service;

import com.banking.cbs.dto.PostTransferRequest;
import com.banking.cbs.dto.PostingResponse;

public interface PostingService {

    PostingResponse postTransfer(PostTransferRequest request);
}