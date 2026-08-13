package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.contract.dto.ContractJournalResponse;
import com.susukkang.fgc.contract.dto.ContractTransactionResponse;
import com.susukkang.fgc.contract.service.ContractJournalProjectionService;
import com.susukkang.fgc.contract.service.ContractTransactionProjectionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "계약", description = "계약 상세 - 검증원장/지급 건 조회 Projection API")
@RestController
@RequestMapping("/api/v1/contracts")
@RequiredArgsConstructor
public class ContractLedgerProjectionController {

    private final ContractJournalProjectionService contractJournalProjectionService;
    private final ContractTransactionProjectionService contractTransactionProjectionService;

    @GetMapping("/{id}/journals")
    public ApiResponse<List<ContractJournalResponse>> getContractJournals(@PathVariable Long id) {
        return ApiResponse.success(contractJournalProjectionService.findJournalsByContractId(id));
    }

    @GetMapping("/{id}/transactions")
    public ApiResponse<List<ContractTransactionResponse>> getContractTransactions(@PathVariable Long id) {
        return ApiResponse.success(contractTransactionProjectionService.findTransactionsByContractId(id));
    }
}
