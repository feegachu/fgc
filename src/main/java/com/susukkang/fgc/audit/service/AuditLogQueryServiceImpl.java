package com.susukkang.fgc.audit.service;

import com.susukkang.fgc.audit.dto.AuditLogResponse;
import com.susukkang.fgc.audit.dto.AuditLogSearchCriteria;
import com.susukkang.fgc.audit.dto.AuditUserRow;
import com.susukkang.fgc.audit.mapper.AuditLogQueryMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * FUN-061 IF-API-52 감사로그 조회 서비스.
 */
@Service
@RequiredArgsConstructor
public class AuditLogQueryServiceImpl implements AuditLogQueryService {

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final String SORT = "occurredAt,desc";

    private final AuditLogQueryMapper auditLogQueryMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(
            String entityType,
            String entityId,
            Long userId,
            String action,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        validatePaging(page, size);
        if (from != null && to != null && from.isAfter(to)) {
            throw validationException("from", "조회 시작일은 종료일보다 늦을 수 없습니다.");
        }

        // to 는 화면의 "종료일 포함" 의미라 다음 날 0시를 exclusive 상한으로 쓴다.
        AuditLogSearchCriteria criteria = new AuditLogSearchCriteria(
                normalize(entityType),
                normalize(entityId),
                userId,
                normalize(action),
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay()
        );

        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            throw validationException("page", "요청할 수 있는 페이지 범위를 초과했습니다.");
        }
        int offset = (int) offsetLong;

        List<AuditLogResponse> content = auditLogQueryMapper
                .selectAuditLogs(criteria, offset, size)
                .stream()
                .map(AuditLogResponse::from)
                .toList();
        long totalElements = auditLogQueryMapper.countAuditLogs(criteria);

        return PageResponse.of(content, page, size, totalElements, SORT);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> actionCodes() {
        return auditLogQueryMapper.selectDistinctActionCodes();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> entityTypes() {
        return auditLogQueryMapper.selectDistinctEntityTypes();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditUserRow> auditUsers() {
        return auditLogQueryMapper.selectAuditUsers();
    }

    private void validatePaging(int page, int size) {
        if (page < MIN_PAGE) {
            throw validationException("page", "page는 1 이상이어야 합니다.");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw validationException("size", "size는 1 이상 100 이하여야 합니다.");
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private FgcBusinessException validationException(String field, String detail) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }
}
