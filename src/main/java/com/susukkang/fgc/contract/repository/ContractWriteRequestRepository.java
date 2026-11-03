package com.susukkang.fgc.contract.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.contract.dto.ContractUpdateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 설명 : IF-API-19 계약 수정의 멱등키별 처음 성공 응답. 정책 작성(policy_write_request)과 같은 방식이다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-30
 */
@Repository
@RequiredArgsConstructor
public class ContractWriteRequestRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record StoredRequest(Long requestedBy, String operation, String requestHash, String responseBody) {
    }

    /** 같은 키의 동시 요청을 트랜잭션 끝까지 막는다. 잠그지 못하면 처리 중인 요청이 있다. */
    public boolean tryLock(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, "contract-request:" + key));
    }

    public Optional<StoredRequest> find(String key) {
        return jdbc.query("""
                SELECT requested_by, operation, request_hash, response_body::text
                  FROM fgc.contract_write_request WHERE idempotency_key = ?
                """, (rs, row) -> new StoredRequest(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                key).stream().findFirst();
    }

    public void save(String key, Long requestedBy, String operation, String hash, ContractUpdateResponse response) {
        jdbc.update("""
                INSERT INTO fgc.contract_write_request
                    (idempotency_key, requested_by, operation, request_hash, response_body, contract_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, key, requestedBy, operation, hash, json(response), response.contractId());
    }

    private String json(ContractUpdateResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("계약 수정 응답을 저장할 수 없습니다.", e);
        }
    }
}
