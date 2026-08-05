package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckDetailLine;
import com.susukkang.fgc.cap.dto.CapCheckInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckRow;
import com.susukkang.fgc.cap.dto.CapCheckStatusCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CapCheckMapper {

    /** cap_check는 append-only 스냅샷. INSERT 후 row.capCheckId에 생성된 PK 가 채워짐 */
    void insertCapCheck(CapCheckInsertRow row);

    /** cap_check_detail 일괄 INSERT. 항목이 없으면 호출 X */
    void insertCapCheckDetails(@Param("details") List<CapCheckDetailInsertRow> details);

    /** 계약·지급단계의 가장 최근 cap_check 1건. 없으면 null. */
    CapCheckRow findLatestByContractAndStage(@Param("contractId") Long contractId,
                                              @Param("paymentStage") String paymentStage);

    /** IF-API-14: 계약 1건의 지급단계별(최대 2건) 최신 cap_check. 합산하지 않고 각 단계 그대로 돌려준다. */
    List<CapCheckRow> findLatestPairByContract(@Param("contractId") Long contractId);

    /** IF-API-31: cap_check 1건 PK 조회. */
    CapCheckRow findById(@Param("capCheckId") Long capCheckId);

    /** cap_check 1건에 속한 산입·제외 근거 라인 전체(detail_seq 순). */
    List<CapCheckDetailLine> findDetailsByCapCheckId(@Param("capCheckId") Long capCheckId);

    /** IF-API-30 목록 검색(페이징). */
    List<CapCheckListRow> search(@Param("month") LocalDate month,
                                  @Param("paymentStage") String paymentStage,
                                  @Param("resultStatus") String resultStatus,
                                  @Param("insurerId") Long insurerId,
                                  @Param("contractNo") String contractNo,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    /** IF-API-30 목록 검색의 전체 건수(페이징용). */
    long count(@Param("month") LocalDate month,
               @Param("paymentStage") String paymentStage,
               @Param("resultStatus") String resultStatus,
               @Param("insurerId") Long insurerId,
               @Param("contractNo") String contractNo);

    /**
     * IF-API-30 요약 카드 4장(정상/주의/위반/검토필요) 집계. resultStatus 필터는 카드 자체의
     * 대상이므로 여기서는 받지 않는다 — month/stage/insurerId/contractNo로만 좁힌다.
     */
    List<CapCheckStatusCount> summarize(@Param("month") LocalDate month,
                                         @Param("paymentStage") String paymentStage,
                                         @Param("insurerId") Long insurerId,
                                         @Param("contractNo") String contractNo);
}
