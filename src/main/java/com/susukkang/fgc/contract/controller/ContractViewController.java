package com.susukkang.fgc.contract.controller;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.contract.code.ContractStatus;
import com.susukkang.fgc.contract.code.DataOrigin;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.service.ContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** FGC-UI-CONT-W01 보험계약 목록 화면. */
@Controller
@RequiredArgsConstructor
public class ContractViewController {

    private final ContractService contractService;

    /** CONT-W02 계약 상세 화면. 탭 데이터는 ContractDetailController의 Ajax API로 조회한다. */
    @GetMapping("/contracts/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("contractId", id);
        return "contract/detail";
    }

    /** CONT-W03 신규 등록 폼. 기준정보와 저장은 화면 전용 JavaScript가 API로 연결한다. */
    @PreAuthorize(Roles.CAN_PROCESS)
    @GetMapping("/contracts/new")
    public String createForm(Model model) {
        model.addAttribute("isEditMode", false);
        model.addAttribute("contractId", null);
        return "contract/form";
    }

    /** CONT-W03 수정 폼. 기존 계약값은 GET /api/v1/contracts/{id}로 복원한다. */
    @PreAuthorize(Roles.CAN_PROCESS)
    @GetMapping("/contracts/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("isEditMode", true);
        model.addAttribute("contractId", id);
        return "contract/form";
    }

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
                DataOrigin.SEED, "시드",
                DataOrigin.NORMALIZED_DB, "정규화 DB",
                DataOrigin.MANUAL, "수동 입력"
        ));
        return "contract/list";
    }
}
