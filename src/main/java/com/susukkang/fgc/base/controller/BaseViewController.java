package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.service.CommissionItemService;
import com.susukkang.fgc.common.util.DateUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;

/**
 * 설명 : 기준정보 조회 화면
 *
 * @author yslee
 * @since 2026-08-11
 * @version 1.2
 */
@Controller
@RequiredArgsConstructor
public class BaseViewController {

    private final CommissionItemService commissionItemService;

    @GetMapping("/base")
    public String index(@ModelAttribute("month") String month, Model model) {
        LocalDate asOf = DateUtil.parseSettlementMonth(month);
        model.addAttribute("asOf", asOf);
        model.addAttribute("commissionItems", commissionItemService.findEffectiveItems(asOf));
        return "base/index";
    }
}
