package com.susukkang.fgc.journal.controller;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalListRow;
import com.susukkang.fgc.journal.dto.JournalSearchCriteria;
import com.susukkang.fgc.journal.dto.JournalSearchResponse;
import com.susukkang.fgc.journal.service.JournalSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : LEDG-W01 검증원장 MPA 조회 컨트롤러
 *
 * @author yslee
 * @since 2026-08-19
 * @version 1.2
 */
@Controller
@RequiredArgsConstructor
public class JournalViewController {

    private static final int PAGE_SIZE = 20;

    private final JournalSearchService journalSearchService;

    @GetMapping("/journals")
    public String list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(name = "type", required = false) String journalType,
            @RequestParam(name = "account", required = false) String accountCode,
            @RequestParam(name = "contract", required = false) Long contractId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "false") boolean searched,
            Model model
    ) {
        // 2026-08-19 yslee - IF-API-34 목록 조회를 인터페이스정의서의 MPA 방식으로 적용
        // 기존 코드: ledger.js가 폼 제출을 가로채 /api/v1/journals를 Ajax로 호출
        // 문제: LEDG-W01 목록은 서버 렌더링 대상으로 정의되어 초기 화면·검색·페이징 계약과 불일치
        // 개선: 조회 버튼을 누른 경우에만 서버에서 검색하고 Thymeleaf 모델로 결과를 렌더링
        String safeType = enumNameOrNull(JournalType.class, journalType);
        String safeStatus = enumNameOrNull(JournalHeaderStatus.class, status);
        LocalDate safeFrom = dateOrNull(from);
        LocalDate safeTo = dateOrNull(to);
        int safePage = Math.max(page, 1);

        JournalSearchResponse journals = emptyPage(safePage);
        if (searched) {
            JournalSearchCriteria criteria = new JournalSearchCriteria(
                    safeFrom, safeTo, safeType, blankToNull(accountCode), contractId, safeStatus);
            PageResponse<JournalListRow> result = journalSearchService.search(criteria, safePage, PAGE_SIZE);
            journals = JournalSearchResponse.from(result);
        }

        model.addAttribute("journals", journals);
        model.addAttribute("searched", searched);
        model.addAttribute("fromFilter", from);
        model.addAttribute("toFilter", to);
        model.addAttribute("typeFilter", safeType);
        model.addAttribute("accountFilter", accountCode);
        model.addAttribute("contractFilter", contractId);
        model.addAttribute("statusFilter", safeStatus);
        model.addAttribute("journalTypes", JournalType.values());
        model.addAttribute("journalStatuses", JournalHeaderStatus.values());
        return "ledger/list";
    }

    private JournalSearchResponse emptyPage(int page) {
        return JournalSearchResponse.from(PageResponse.of(List.of(), page, PAGE_SIZE, 0, "journalDate,desc"));
    }

    private LocalDate dateOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private <E extends Enum<E>> String enumNameOrNull(Class<E> enumType, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value).name();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
