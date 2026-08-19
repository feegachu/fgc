package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapAgentSummaryRow;
import com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckDetailLine;
import com.susukkang.fgc.cap.dto.CapCheckInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckRow;
import com.susukkang.fgc.cap.dto.CapCheckStatusCount;
import com.susukkang.fgc.cap.dto.CapStageSummaryRow;
import com.susukkang.fgc.common.code.PaymentStage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CapCheckMapper {

    /**
     * cap_check upsert — INSERT 후 row.capCheckId에 생성/갱신된 PK가 채워짐
     * (validation_run_id, contract_id, payment_stage)가 이미 있으면(같은 실행 안에서의 재계산·
     * 재시도) 새로 계산한 값으로 덮어쓴다 — FINALIZED 이전에는 DB가 이를 허용
     * REALTIME(validationRunId=null)은 NULL끼리 유니크 충돌이 나지 않아 항상 순수 INSERT처럼 동작
     */
    void insertCapCheck(CapCheckInsertRow row);

    /**
     * cap_check_detail 일괄 upsert — (cap_check_id, detail_seq)가 이미 있으면 새 계산값으로
     * 덮어쓴다. 항목이 없으면 호출 X.
     */
    void insertCapCheckDetails(@Param("details") List<CapCheckDetailInsertRow> details);

    /**
     * insertCapCheck가 UPDATE 경로를 탔을 때, 이번 재계산이 실제로 채운 마지막 detail_seq보다
     * 큰(=예전 계산엔 있었지만 이번엔 없어진) 뒷자리 detail 행만 잘라낸다. maxDetailSeq가 0이면
     * (이번 계산에 detail이 하나도 없으면) 전부 지운다.
     */
    void pruneCapCheckDetails(@Param("capCheckId") Long capCheckId, @Param("maxDetailSeq") int maxDetailSeq);

    /** 계약·지급단계의 가장 최근 cap_check 1건. 없으면 null. */
    CapCheckRow findLatestByContractAndStage(@Param("contractId") Long contractId,
                                              @Param("paymentStage") String paymentStage);

    /** cap_check 1건에 속한 산입·제외 근거 라인 전체(detail_seq 순). */
    List<CapCheckDetailLine> findDetailsByCapCheckId(@Param("capCheckId") Long capCheckId);

    /** IF-API-30 목록 검색(페이징). */
    List<CapCheckListRow> search(@Param("month") LocalDate month,
                                  @Param("paymentStage") String paymentStage,
                                  @Param("resultStatus") String resultStatus,
                                  @Param("insurerId") Long insurerId,
                                  @Param("organizationId") Long organizationId,
                                  @Param("contractNo") String contractNo,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    /** IF-API-30 목록 검색의 전체 건수(페이징용). */
    long count(@Param("month") LocalDate month,
               @Param("paymentStage") String paymentStage,
               @Param("resultStatus") String resultStatus,
               @Param("insurerId") Long insurerId,
               @Param("organizationId") Long organizationId,
               @Param("contractNo") String contractNo);

    /**
     * IF-API-30 요약 카드 4장(정상/주의/위반/검토필요) 집계. resultStatus 필터는 카드 자체의
     * 대상이므로 여기서는 받지 않는다 — month/stage/insurerId/contractNo로만 좁힌다.
     */
    List<CapCheckStatusCount> summarize(@Param("month") LocalDate month,
                                         @Param("paymentStage") String paymentStage,
                                         @Param("insurerId") Long insurerId,
                                         @Param("organizationId") Long organizationId,
                                         @Param("contractNo") String contractNo);

    /** status와 stage를 제외한 전체 검색범위의 지급단계별 집계. */
    List<CapStageSummaryRow> summarizeByStage(
            @Param("month") LocalDate month,
            @Param("insurerId") Long insurerId,
            @Param("organizationId") Long organizationId,
            @Param("contractNo") String contractNo);

    /** status와 stage를 제외한 전체 검색범위의 GA_TO_FC 설계사별 모니터링 집계. */
    List<CapAgentSummaryRow> summarizeByAgent(
            @Param("month") LocalDate month,
            @Param("insurerId") Long insurerId,
            @Param("organizationId") Long organizationId,
            @Param("contractNo") String contractNo);

    /**
     * IF-API-31(계산근거 팝업)용. cap_check_id로 cap_check 1건 + contractNo(insurance_contract join)를
     * 조회한다. 없으면 null.
     */
    CapCheckRow findById(@Param("capCheckId") Long capCheckId);
    
    /**
     * 설명 : DB에 이미 저장된 준법감시 증빙 대상 금액을 조회
     *
     * @param  contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 준법감시 증빙 대상 금액
     * @author hjKang
     * @since 2026-08-14
     */
    BigDecimal selectComplianceEvidenceAmount(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage);

    /** 계약 체결일·상품·채널 기준으로 해당 지급 단계에 적용 가능한 활성 Cap 룰이 있는지 확인한다. */
    boolean existsApplicableRuleSet(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage);
}
