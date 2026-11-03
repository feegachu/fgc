package com.susukkang.fgc.policy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.code.ScheduleEligibilityParameter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 설명 : FUN-015 계약상태별 지급조건 정책 해석. 계약일에 적용되는 SCHEDULE_ELIGIBILITY 정책 하나를 고르고
 * 그 파라미터가 레지스트리({@link ScheduleEligibilityParameter})와 같은지 확인한다. 조회만 한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-01
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleEligibilityPolicyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * 계약일로 버전을 고른다(운영정책서 제49조: 승계 전 계약은 구버전, V68 계산용 적용 구간).
     * 정책이 없거나 여럿이거나 파라미터가 레지스트리와 다르면 통과로 보지 않는다.
     *
     * @return 적용할 정책 버전 ID
     * @throws FgcBusinessException FGC-IMPL-POLICY_MISSING(422)
     */
    public Long resolve(LocalDate contractDate) {
        List<Long> candidates = jdbc.queryForList("""
                SELECT policy_version_id FROM fgc.policy_application_version
                 WHERE policy_type = 'SCHEDULE_ELIGIBILITY'
                   AND effective_from <= ? AND (application_to IS NULL OR application_to >= ?)
                """, Long.class, contractDate, contractDate);
        if (candidates.size() != 1) {
            // 최신 판이나 LIMIT 1로 중복을 숨기지 않는다(인터페이스정의서 §4-4-1).
            throw missing(contractDate, candidates.isEmpty()
                    ? "계약일에 적용할 상태별 지급조건 정책이 없습니다."
                    : "계약일에 적용할 상태별 지급조건 정책이 여러 개라 하나를 고를 수 없습니다.");
        }
        Long policyVersionId = candidates.get(0);
        Map<String, JsonNode> parameters = new HashMap<>();
        jdbc.query("SELECT parameter_key, parameter_value::text FROM fgc.policy_parameter WHERE policy_version_id = ?",
                rs -> {
                    parameters.put(rs.getString(1), json(rs.getString(2)));
                }, policyVersionId);
        boolean registered = parameters.keySet().equals(ScheduleEligibilityParameter.KEYS)
                && parameters.entrySet().stream()
                .allMatch(parameter -> ScheduleEligibilityParameter.accepts(parameter.getKey(), parameter.getValue()));
        if (!registered) {
            throw missing(contractDate, "상태별 지급조건 정책(" + policyVersionId + ")의 파라미터가 구현된 값과 다릅니다.");
        }
        return policyVersionId;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("policy_parameter JSON을 읽을 수 없습니다.", exception);
        }
    }

    private static FgcBusinessException missing(LocalDate contractDate, String detail) {
        return new FgcBusinessException(FgcErrorCode.IMPL_POLICY_MISSING, "contractDate",
                Map.of("contractDate", contractDate.toString()), detail);
    }
}
