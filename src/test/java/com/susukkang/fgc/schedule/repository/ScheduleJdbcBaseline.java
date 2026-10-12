package com.susukkang.fgc.schedule.repository;

import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.schedule.dto.*;
import com.susukkang.fgc.common.code.PaymentStage;
import java.util.List;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class ScheduleJdbcBaseline {
    private final JdbcBaseline jdbc;
    public ScheduleJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public List<ScheduleHeaderResponse> selectByCondition(ScheduleSearchCondition condition, int size, long offset) {
        var parameters = new HashMap<String, Object>();
        parameters.put("condition", condition);
        parameters.put("size", size);
        parameters.put("offset", offset);
        return jdbc.selectList("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectByCondition", parameters);
    }
    public List<ScheduleHeaderResponse> selectAllByCondition(ScheduleSearchCondition condition) {
        var parameters = new HashMap<String, Object>();
        parameters.put("condition", condition);
        return jdbc.selectList("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectAllByCondition", parameters);
    }
    public long countByCondition(ScheduleSearchCondition condition) {
        var parameters = new HashMap<String, Object>();
        parameters.put("condition", condition);
        return jdbc.selectOne("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.countByCondition", parameters);
    }
    public int insertScheduleHeader(ScheduleHeaderInsertDTO header) {
        return jdbc.update("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.insertScheduleHeader", header);
    }
    public ScheduleDetailResponse selectScheduleDetailById(Long scheduleHeaderId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("scheduleHeaderId", scheduleHeaderId);
        return jdbc.selectOne("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectScheduleDetailById", parameters);
    }
    public List<ScheduleHeaderResponse> selectVersionsByScheduleHeaderId(Long scheduleHeaderId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("scheduleHeaderId", scheduleHeaderId);
        return jdbc.selectList("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectVersionsByScheduleHeaderId", parameters);
    }
    public int insertAllScheduleLines(List<ScheduleLineInsertDTO> scheduleLineList) {
        var parameters = new HashMap<String, Object>();
        parameters.put("lines", scheduleLineList);
        return jdbc.update("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.insertAllScheduleLines", parameters);
    }
    public int linkHeadersToValidationRun(List<Long> scheduleHeaderIds, Long validationRunId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("scheduleHeaderIds", scheduleHeaderIds);
        parameters.put("validationRunId", validationRunId);
        return jdbc.update("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.linkHeadersToValidationRun", parameters);
    }
    public int upsertPolicyReviewCase(Long contractId, PaymentStage paymentStage, String exceptionType, String title, String description) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        parameters.put("exceptionType", exceptionType);
        parameters.put("title", title);
        parameters.put("description", description);
        return jdbc.update("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.upsertPolicyReviewCase", parameters);
    }
    public Long lockContractForScheduleGeneration(Long contractId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        return jdbc.selectOne("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.lockContractForScheduleGeneration", parameters);
    }
    public Long selectActiveOperationalPolicyVersionId(Long contractId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectOne("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectActiveOperationalPolicyVersionId", parameters);
    }
    public int selectNextScheduleVersionNo(Long contractId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectOne("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectNextScheduleVersionNo", parameters);
    }
    public int deactivateActiveOperationalSchedule(Long contractId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.update("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.deactivateActiveOperationalSchedule", parameters);
    }
    public List<Long> selectActiveOperationalScheduleIds(Long contractId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        return jdbc.selectList("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectActiveOperationalScheduleIds", parameters);
    }
    public ScheduleDetailResponse selectByContractIdAndPaymentStage(Long contractId, PaymentStage paymentStage) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        return jdbc.selectOne("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectByContractIdAndPaymentStage", parameters);
    }
    public ScheduleHeaderInsertDTO selectScheduleHeaderById(Long scheduleId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("scheduleId", scheduleId);
        return jdbc.selectOne("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectScheduleHeaderById", parameters);
    }
    public int confirmPlannedScheduleLines(Long scheduleHeaderId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("scheduleHeaderId", scheduleHeaderId);
        return jdbc.update("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.confirmPlannedScheduleLines", parameters);
    }
    public int confirmScheduleHeader(Long scheduleHeaderId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("scheduleHeaderId", scheduleHeaderId);
        return jdbc.update("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.confirmScheduleHeader", parameters);
    }
    public List<ScheduleLineInsertDTO> selectScheduleLinesByScheduleId(Long scheduleId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("scheduleId", scheduleId);
        return jdbc.selectList("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.selectScheduleLinesByScheduleId", parameters);
    }
    public int updateScheduleHeaderStatus(Long scheduleHeaderId, ScheduleHeaderStatus scheduleHeaderStatus, boolean active) {
        var parameters = new HashMap<String, Object>();
        parameters.put("scheduleHeaderId", scheduleHeaderId);
        parameters.put("scheduleHeaderStatus", scheduleHeaderStatus);
        parameters.put("active", active);
        return jdbc.update("com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline.updateScheduleHeaderStatus", parameters);
    }
}
