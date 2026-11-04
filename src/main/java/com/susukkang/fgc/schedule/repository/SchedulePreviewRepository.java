package com.susukkang.fgc.schedule.repository;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
@Repository
@RequiredArgsConstructor
public class SchedulePreviewRepository {
    private final JdbcTemplate jdbc;
    public boolean lockRequest(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class, "schedule-preview:" + key));
    }
    public List<Map<String, Object>> request(String key) {
        return jdbc.queryForList("SELECT user_id, after_value::text AS result FROM fgc.audit_log WHERE action_code='SCHEDULE_PREVIEW_CREATED' AND entity_type='SCHEDULE_REQUEST' AND entity_id=?", key);
    }
    public String sourceVersion(Long contract, PaymentStage stage) {
        return jdbc.queryForObject("""
          SELECT md5(row_to_json(c)::text || ':' || row_to_json(po)::text || ':' || COALESCE((SELECT max(schedule_header_id)::text
          FROM fgc.schedule_header WHERE contract_id=c.contract_id AND payment_stage=?), '0'))
          FROM fgc.insurance_contract c JOIN fgc.product_offering po ON po.product_offering_id=c.product_offering_id WHERE c.contract_id=?
          """, String.class, stage.name(), contract);
    }
    public void lockInputs(Long contract, Long policy) {
        jdbc.queryForList("SELECT po.product_offering_id FROM fgc.product_offering po JOIN fgc.insurance_contract c ON c.product_offering_id=po.product_offering_id WHERE c.contract_id=? FOR SHARE OF po", Long.class, contract);
        jdbc.queryForList("SELECT policy_version_id FROM fgc.policy_version WHERE policy_version_id=? FOR SHARE", Long.class, policy);
    }
    public int nextVersion(Long contract, PaymentStage stage, String scenario) {
        return jdbc.queryForObject("SELECT COALESCE(max(schedule_version_no),0)+1 FROM fgc.schedule_header WHERE contract_id=? AND payment_stage=? AND schedule_purpose='SIMULATION' AND scenario_code=?", Integer.class, contract, stage.name(), scenario);
    }
    public Long active(Long contract, PaymentStage stage, String scenario) {
        return jdbc.queryForList("SELECT schedule_header_id FROM fgc.schedule_header WHERE contract_id=? AND payment_stage=? AND schedule_purpose='SIMULATION' AND scenario_code=? AND active_yn", Long.class, contract, stage.name(), scenario).stream().findFirst().orElse(null);
    }
    public List<Long> activePreviews(Long contract, PaymentStage stage) {
        return jdbc.queryForList("SELECT schedule_header_id FROM fgc.schedule_header WHERE contract_id=? AND payment_stage=? AND schedule_purpose='SIMULATION' AND active_yn ORDER BY schedule_header_id DESC", Long.class, contract, stage.name());
    }
    public void deactivate(Long id) {
        if (id != null) jdbc.update("UPDATE fgc.schedule_header SET active_yn=false WHERE schedule_header_id=?", id);
    }
}
