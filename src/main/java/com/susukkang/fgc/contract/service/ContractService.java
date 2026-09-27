package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractCreateResponse;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractStatusEventResponse;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.dto.ContractUpdateResponse;
import com.susukkang.fgc.contract.dto.ContractView;

import java.util.List;

/**
 * 설명 : 보험계약 조회·생성·수정과 상태사건 조회·한도 재검증·스케줄 재생성 기능을 정의한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
public interface ContractService {

    /**
     * 설명 : 검색 조건에 따라 계약을 조회한다.
     * 검색 조건과 현재 페이지 , 최대 계약수를 받아
     * 보험 계약 목록을 출력하고 최대페이지 , 현재페이지를 PageResponse를 통해 출력
     *
     * @param condition 조회 조건
     * @param page 현재 페이지
     * @param size 한 페이지에 출력할 계약 수
     * @return 계약 목록과 페이지 번호·전체 건수를 포함한 페이지 응답
     * @author hjKang
     * @since 2026-08-05
     */
    PageResponse<ContractView> selectByCondition(ContractSearchCondition condition, int page, int size);

    /**
     * 설명 : 검색 조건에 해당하는 전체 보험계약을 조회한다.
     * CSV 내보내기에 사용하며, 페이징 없이 계약 ID 내림차순으로 반환한다.
     *
     * @param condition 보험회사·상품·설계사·조직·계약상태·계약일 등의 검색 조건
     * @return 검색 조건에 해당하는 전체 계약 목록. 조회 결과가 없으면 빈 목록
     * @author hjKang
     * @since 2026-09-27
     */
    List<ContractView> selectAllByCondition(
            ContractSearchCondition condition
    );

    /**
     * 설명 : 계약 상세보기 서비스
     *       계약 상태 사건 이력은 IF-API-16에서 별도 조회한다.
     * @param  contractId 계약 ID
     * @return 계약 기본정보
     * @author hjKang
     * @since 2026-08-05
     */
    ContractDetailResponse selectContractDetailById(Long contractId);

    /**
     * 설명 : IF-API-16 계약 상태사건과 Job별 처리 이력을 효력일시 순으로 반환한다.
     * 처리일은 사건 원본의 폐기 예정 processed_at이 아니라 Job별 처리 테이블에서 읽는다.
     *
     * @param contractId 보험계약 ID
     * @return 상태사건별 처리 이력이 포함된 목록
     * @author hjKang
     * @since 2026-09-27
     */
    List<ContractStatusEventResponse> selectStatusEventsByContractId(Long contractId);

    /**
     * 설명 : 보험계약 등록 요청을 검증하고 계약을 저장한다.
     *
     * @param request 보험계약 등록 요청
     * @return 등록된 보험계약 정보
     * @author hjKang
     * @since 2026-08-05
     */
    ContractCreateResponse createContract(ContractCreateRequest request);

    /**
     * 설명 : 계약을 수정하고 스케줄 산정 정보가 변경된 경우 재생성과 한도 검증을 수행한다.
     * 수정 전·후 값은 같은 트랜잭션의 감사 로그에 기록한다.
     *
     * @param id 수정할 보험계약 ID
     * @param request 보험계약 수정 요청
     * @return 계약 ID와 생성·재생성된 스케줄 헤더 ID 목록
     * @author hjKang
     * @since 2026-08-05
     */
    ContractUpdateResponse updateContract(Long id, ContractUpdateRequest request);

    /**
     * 설명 : 현재 계약·운영 스케줄을 기준으로 지급단계별 한도를 다시 검증한다.
     *
     * @param contractId 보험계약 ID
     * @return 지급단계별 최신 한도 판정 결과
     * @author hjKang
     * @since 2026-09-27
     */
    List<CapCheckSaveResult> recheckCap(Long contractId);

    /**
     * 설명 : 현재 계약·정책을 기준으로 운영 스케줄을 재생성하고 사유를 기록한다.
     *
     * @param contractId 보험계약 ID
     * @param reason 스케줄 재생성 사유
     * @return 생성·재생성된 스케줄 헤더 ID 목록
     * @author hjKang
     * @since 2026-09-27
     */
    List<Long> regenerateSchedules(Long contractId, String reason);
}
