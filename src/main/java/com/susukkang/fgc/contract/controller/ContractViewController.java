package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.service.ContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** FGC-UI-CONT-W01 보험계약 목록 화면. */
@Controller
@RequiredArgsConstructor
public class ContractViewController {

    private final ContractService contractService;

    @GetMapping("/contracts")
    public String list(
            @ModelAttribute("condition") ContractSearchCondition condition,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model
    ) {
        model.addAttribute("contracts", contractService.selectByCondition(condition, page, size));
        model.addAttribute("contractStatuses", ContractStatus.values());
        model.addAttribute("capResultStatuses", CapResultStatus.values());
        model.addAttribute("contractStatusLabels", Map.of(
                ContractStatus.APPLIED, "청약",
                ContractStatus.ACTIVE, "유지",
                ContractStatus.UNPAID, "미납",
                ContractStatus.LAPSED, "실효",
                ContractStatus.REVIVED, "부활",
                ContractStatus.CANCELLED, "청약철회",
                ContractStatus.TERMINATED, "해지",
                ContractStatus.MATURED, "만기"
        ));
        model.addAttribute("dataOriginLabels", Map.of(
                com.susukkang.fgc.contract.domain.DataOrigin.SEED, "시드",
                com.susukkang.fgc.contract.domain.DataOrigin.NORMALIZED_DB, "정규화 DB",
                com.susukkang.fgc.contract.domain.DataOrigin.MANUAL, "수동 입력"
        ));
        return "contract/list";
    }
}
