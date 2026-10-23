package com.susukkang.fgc.ai.repository;

import com.susukkang.fgc.ai.entity.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 설명 : ai_call_log 저장 전용 Repository. 사용액(예산 10달러, SRC-060)은 아래 SQL 1줄로 본다 —
 * 단가는 gpt-4o-mini 기준(입력 $0.15·출력 $0.60 per 1M 토큰, SRC-060)이므로 모델을 바꾸면 단가도 바꾼다.
 * <pre>
 * SELECT model_name, sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens, round(sum(input_tokens) * 0.15 / 1e6 + sum(output_tokens) * 0.60 / 1e6, 4) AS usd FROM fgc.ai_call_log GROUP BY model_name;
 * </pre>
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-23
 */
public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {
}
