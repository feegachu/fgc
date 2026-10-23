package com.susukkang.fgc.ai.entity;

import com.susukkang.fgc.common.code.AiCallPurpose;
import com.susukkang.fgc.common.code.AiCallStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 설명 : 외부 LLM 호출 1건의 기록(SER-013·IF-EXT-03). 추가 기록 전용이며 프롬프트·응답 원문은 담지 않는다(SER-006).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-23
 */
@Entity
@Table(name = "ai_call_log")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiCallLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_call_log_id")
    private Long aiCallLogId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private AiCallPurpose purpose;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "call_status", nullable = false, length = 20)
    private AiCallStatus callStatus;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "related_entity_type", length = 40)
    private String relatedEntityType;

    @Column(name = "related_entity_id")
    private Long relatedEntityId;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "requested_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    /**
     * 설명 : ID 없는 신규 호출 기록을 만든다.
     *
     * @author yslee
     * @version 1.0
     * @since 2026-10-23
     */
    public static AiCallLog create(AiCallPurpose purpose, String modelName, Integer inputTokens, Integer outputTokens,
                                   Integer latencyMs, AiCallStatus callStatus, String errorCode,
                                   String relatedEntityType, Long relatedEntityId, Long requestedBy) {
        AiCallLog log = new AiCallLog();
        log.purpose = purpose;
        log.modelName = modelName;
        log.inputTokens = inputTokens;
        log.outputTokens = outputTokens;
        log.latencyMs = latencyMs;
        log.callStatus = callStatus;
        log.errorCode = errorCode;
        log.relatedEntityType = relatedEntityType;
        log.relatedEntityId = relatedEntityId;
        log.requestedBy = requestedBy;
        return log;
    }
}
