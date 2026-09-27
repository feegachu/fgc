package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.DataOrigin;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PremiumConversionRuleCode;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractCreateResponse;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import com.susukkang.fgc.contract.dto.ContractInput;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractStatusEventProcessingResponse;
import com.susukkang.fgc.contract.dto.ContractStatusEventProcessingRow;
import com.susukkang.fgc.contract.dto.ContractStatusEventResponse;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.dto.ContractUpdateResponse;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.ContractFinancialSnapshotRepository;
import com.susukkang.fgc.contract.repository.ContractQueryRepository;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import com.susukkang.fgc.schedule.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 설명 : 보험계약 서비스의 조회·생성·수정·재검증 업무를 구현한다.
 * 공개 업무 메서드를 먼저 배치하고 검증·변환·후속 처리 보조 메서드를 아래에 모은다.
 *
 * @author hjKang
 * @since 2026-08-05
 * @version 1.2
 */
@Service
@Validated
@RequiredArgsConstructor
public class ContractServiceImpl implements ContractService {

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final String SORT = "contractId,desc";
    // FUN-061·운영정책서 제51조 "계약·상태 사건" — 등록·수정과 같은 트랜잭션에서 감사행을 남긴다.
    private static final String AUDIT_ENTITY_TYPE = "CONTRACT";
    private static final String AUDIT_CONTRACT_CREATED = "CONTRACT_CREATED";
    private static final String AUDIT_CONTRACT_UPDATED = "CONTRACT_UPDATED";
    private static final String AUDIT_CAP_RECHECKED = "CONTRACT_CAP_RECHECKED";
    private static final String AUDIT_SCHEDULES_REGENERATED = "CONTRACT_SCHEDULES_REGENERATED";

    private final CapCheckService capCheckService;
    private final ScheduleService scheduleService;
    private final AuditLogService auditLogService;
    private final ContractQueryRepository contractQueryRepository;
    private final InsuranceContractRepository insuranceContractRepository;
    private final ContractFinancialSnapshotRepository contractFinancialSnapshotRepository;
    private final ContractStatusEventRepository contractStatusEventRepository;

    @Override
    public PageResponse<ContractView> selectByCondition(ContractSearchCondition condition, int page, int size) {
        // 1. 페이지 번호·크기와 조회 가능한 offset 범위 검증
        validatePaging(page, size);

        // 2. API의 1부터 시작하는 페이지 번호를 JPA의 0부터 시작하는 번호로 변환
        Pageable pageable = PageRequest.of(
                page - 1,
                size,
                Sort.by(Sort.Direction.DESC, "contractId")
        );

        // 3. 검색 조건에 맞는 현재 페이지 목록과 전체 건수 조회
        Page<ContractView> result =
                contractQueryRepository.search(condition, pageable);

        // 4. 조회 결과를 API의 페이지 번호·전체 건수·정렬 정보와 함께 반환
        return PageResponse.of(
                result.getContent(),
                page,
                size,
                result.getTotalElements(),
                SORT
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractView> selectAllByCondition(
            ContractSearchCondition condition
    ) {
        // 1. CSV에 사용할 전체 검색 결과를 페이징 없이 계약 ID 내림차순으로 반환
        return contractQueryRepository.searchAll(condition);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractDetailResponse selectContractDetailById(Long contractId) {
        // 1. 보험회사·상품·설계사·조직을 포함한 계약 상세 조회(없으면 오류 반환)
        return contractQueryRepository.findDetailById(contractId)
                .orElseThrow(() -> validationException("contractId", "존재하지 않는 보험계약입니다."));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractStatusEventResponse> selectStatusEventsByContractId(Long contractId) {
        // 1. 계약 존재 확인
        validateContractExists(contractId);

        // 2. 계약의 상태사건을 효력일시·사건 순번 오름차순으로 조회
        List<ContractStatusEvent> events =
                contractStatusEventRepository.findByContractIdOrderByEffectiveAtAscEventSeqAsc(contractId);
        // 3. 상태사건이 없으면 처리 이력 조회 없이 빈 목록 반환
        if (events.isEmpty()) {
            return List.of();
        }

        // 4. Job별 처리 이력을 한 번에 조회하고 상태사건 ID별로 묶기
        Map<Long, List<ContractStatusEventProcessingResponse>> processingsByEvent = new LinkedHashMap<>();
        for (ContractStatusEventProcessingRow processing
                : contractQueryRepository.findStatusEventProcessingsByContractId(contractId)) {
            processingsByEvent
                    .computeIfAbsent(processing.contractStatusEventId(), ignored -> new ArrayList<>())
                    .add(processing.toResponse());
        }

        // 5. 각 상태사건에 처리 이력을 붙여 반환(미처리 사건은 빈 처리 목록)
        return events.stream()
                .map(event -> new ContractStatusEventResponse(
                        event.getEventSeq(),
                        event.getPreviousStatus(),
                        event.getNewStatus(),
                        event.getEffectiveAt(),
                        event.getReceivedAt(),
                        processingsByEvent.getOrDefault(event.getContractStatusEventId(), List.of()),
                        event.getSourceSystem(),
                        event.getSourceEventKey()
                ))
                .toList();
    }

    @Override
    @Transactional
    public ContractCreateResponse createContract(ContractCreateRequest request) {
        // 1. 계약일·기준정보·소속 관계와 계약번호 중복 여부 검증
        validateInput(request);

        // 2. 지원하는 납입주기인지 확인하고 직접 입력 보험료의 환산 코드 결정
        PremiumConversionRuleCode conversionRuleCode =
                determineConversionRuleCode(
                        request.getPaymentCycleCode()
                );
        // 3. 검증된 요청을 수기 등록(MANUAL) 출처의 계약 엔티티로 변환
        var contractEntity = toContractEntity(request, conversionRuleCode);
        // 4. 계약을 저장하고 DB에 반영하여 ID와 후속 스냅샷·스케줄 조회에 사용할 행 확보
        InsuranceContract savedContract = insuranceContractRepository.saveAndFlush(contractEntity);
        Long contractId = savedContract.getContractId();

        // 5. 계약일·초회 보험료를 기준으로 최초 재무 스냅샷 생성(중복이면 생략)
        contractFinancialSnapshotRepository
                .insertInitialIfAbsent(contractId);
        // 6. FUN-036: 같은 트랜잭션에서 보험회사 → GA, GA → FC 예상 스케줄 생성
        // 정책 없음·중복 지급단계는 exception_case 검토 큐에 등록되고 생성에서 제외된다.
        ScheduleGenerationResult scheduleResult =
                scheduleService.generateSchedulesByContractId(contractId);

        // 7. FUN-030: 활성 운영 스케줄이 있는 지급단계별로 1,200% 한도 검증
        checkCapsForActiveSchedules(contractId, CapCheckKind.REALTIME);

        // 8. 계약일의 서울 자정을 효력일시로 최초 상태사건(event_seq=1) 저장
        // 계약 상세의 상태 변경 이력에서 생성 당시 상태를 확인할 수 있게 한다.
        OffsetDateTime receivedAt = DateUtil.nowSeoul();
        OffsetDateTime effectiveAt = request.getContractDate()
                .atStartOfDay(DateUtil.SEOUL_ZONE)
                .toOffsetDateTime();
        ContractStatusEvent event = toInitialStatusEvent(
                contractId,
                request.getContractStatus(),
                effectiveAt,
                receivedAt
        );
        contractStatusEventRepository.saveAndFlush(event);

        // 9. 생성된 계약의 감사 스냅샷을 같은 트랜잭션에 기록
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode(AUDIT_CONTRACT_CREATED)
                .entityType(AUDIT_ENTITY_TYPE)
                .entityId(String.valueOf(contractId))
                .after(toAuditSnapshot(savedContract, null))
                .build());

        // 10. 생성한 계약 ID와 예상 스케줄 헤더 ID 목록 반환
        return ContractCreateResponse.builder()
                .contractId(contractId)
                .scheduleHeaderIds(scheduleResult.scheduleHeaderIds())
                .build();
    }

    @Override
    @Transactional
    public ContractUpdateResponse updateContract(Long id, ContractUpdateRequest request) {
        // 1. 계약 ID를 검증하고 수정할 계약 엔티티 조회
        if (id == null) {
            throw validationException("contractId", "존재하지 않는 보험계약입니다.");
        }
        InsuranceContract currentContract = insuranceContractRepository.findById(id)
                .orElseThrow(() -> validationException("contractId", "존재하지 않는 보험계약입니다."));
        // 2. 수정 요청의 계약일·기준정보·소속 관계와 계약번호 중복 여부 검증
        validateInputForUpdate(currentContract, request);

        // 3. 지원하는 납입주기인지 확인하고 직접 입력 보험료의 환산 코드 결정
        PremiumConversionRuleCode conversionRuleCode =
                determineConversionRuleCode(
                        request.getPaymentCycleCode()
                );
        // 4. 엔티티 변경 전에 스케줄 재생성 여부를 판단하고 수정 전 감사 값을 복사
        boolean scheduleImpactingChanges = hasScheduleImpactingChanges(currentContract, request);
        Map<String, Object> before = toAuditSnapshot(currentContract, currentContract.getUpdatedAt());
        // 5. 관리 중인 계약 엔티티에 수정값 반영(ID와 데이터 출처는 유지)
        currentContract.updateDetails(
                request.getInsurerId(),
                request.getProductOfferingId(),
                request.getContractNo(),
                request.getContractDate(),
                request.getAgentId(),
                request.getOrganizationId(),
                request.getPremiumPerCycleAmount(),
                request.getFirstPremiumAmount(),
                request.getMonthlyEquivalentFirstPremium(),
                conversionRuleCode,
                request.getPaymentCycleCode(),
                request.getPaymentTermMonths(),
                request.getStandardSurrenderDeductionAmount(),
                request.getContractStatus()
        );
        // 6. 변경 감지를 DB에 반영하여 후속 네이티브 SQL·MyBatis 조회에 수정값 전달
        insuranceContractRepository.flush();

        // 7. 수정된 계약일 기준 최초 재무 스냅샷이 없으면 생성
        // 같은 계약·기준일·환급금 유형은 중복 생성하지 않는다.
        // 계약일이 바뀌면 새 날짜의 스냅샷을 추가하고 기존 스냅샷은 이력으로 보존한다.
        contractFinancialSnapshotRepository.insertInitialIfAbsent(id);
        // 8. 보험료·상품·설계사 등 스케줄 산정 정보가 바뀐 경우에만 재생성과 한도 검증 수행
        List<Long> scheduleHeaderIds = List.of();
        if (scheduleImpactingChanges) {
            // 8-1. 수정된 계약을 기준으로 운영 스케줄 재생성
            scheduleHeaderIds = scheduleService.regenerateContractSchedules(
                    id,
                    "CONTRACT_UPDATED"
            );

            // 8-2. 활성 운영 스케줄이 있는 지급단계를 실시간 한도 검증
            checkCapsForActiveSchedules(id, CapCheckKind.REALTIME);
        }

        /*
         * TODO(FUN-026, 2차)
         * 수정 전 상태와 요청 상태가 다른 경우
         * 계약상태 사건 이력을 등록한다.
         */

        // 9. 복사해 둔 수정 전 값과 수정 후 값을 감사 로그에 기록
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode(AUDIT_CONTRACT_UPDATED)
                .entityType(AUDIT_ENTITY_TYPE)
                .entityId(String.valueOf(id))
                .before(before)
                .after(toAuditSnapshot(currentContract, null))
                .build());

        // 10. 계약 ID와 재생성된 스케줄 ID 반환(재생성이 없으면 빈 목록)
        return ContractUpdateResponse.builder()
                .contractId(id)
                .scheduleHeaderIds(scheduleHeaderIds)
                .regeneratedScheduleIds(scheduleHeaderIds)
                .build();
    }

    @Override
    @Transactional
    public List<CapCheckSaveResult> recheckCap(Long contractId) {
        // 1. 계약 존재 확인
        validateContractExists(contractId);

        // 2. 지급단계별 활성 운영 스케줄을 기준으로 한도 재검증
        checkCapsForActiveSchedules(contractId, CapCheckKind.MANUAL);

        // 3. 지급단계별로 존재하는 최신 한도 판정 결과 조회
        List<CapCheckSaveResult> results = Arrays.stream(PaymentStage.values())
                .map(stage -> capCheckService.findLatest(contractId, stage))
                .flatMap(Optional::stream)
                .toList();
        // 4. 재검증 결과를 감사 로그에 기록
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode(AUDIT_CAP_RECHECKED)
                .entityType(AUDIT_ENTITY_TYPE)
                .entityId(String.valueOf(contractId))
                .after(results)
                .build());
        // 5. 최신 판정 결과 목록 반환
        return results;
    }

    @Override
    @Transactional
    public List<Long> regenerateSchedules(Long contractId, String reason) {
        // 1. 계약 존재 확인
        validateContractExists(contractId);
        // 2. 사유와 현재 계약·정책을 기준으로 운영 스케줄 재생성
        List<Long> scheduleIds = scheduleService.regenerateContractSchedules(contractId, reason);
        // 3. 재생성한 스케줄 ID와 요청 사유를 감사 로그에 기록
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode(AUDIT_SCHEDULES_REGENERATED)
                .entityType(AUDIT_ENTITY_TYPE)
                .entityId(String.valueOf(contractId))
                .after(scheduleIds)
                .reason(reason)
                .build());
        // 4. 생성·재생성된 스케줄 헤더 ID 목록 반환
        return scheduleIds;
    }

    private void validatePaging(int page, int size) {
        // 1. API 페이지 번호의 최솟값 검증
        if (page < MIN_PAGE) {
            throw validationException(
                    "page",
                    "page는 " + MIN_PAGE + " 이상이어야 합니다."
            );
        }

        // 2. 한 페이지에서 조회할 계약 수의 허용 범위 검증
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw validationException(
                    "size",
                    "size는 " + MIN_SIZE + " 이상 " + MAX_SIZE + " 이하여야 합니다."
            );
        }
        // 3. JPA 조회 offset의 int 범위를 넘지 않는지 long으로 계산하여 검증
        long offset = (long) (page - 1) * size;

        if (offset > Integer.MAX_VALUE) {
            throw validationException(
                    "page",
                    "요청할 수 있는 페이지 범위를 초과했습니다."
            );
        }
    }

    private void validateContractExists(Long contractId) {
        if (contractId == null || !insuranceContractRepository.existsById(contractId)) {
            throw validationException("contractId", "존재하지 않는 보험계약입니다.");
        }
    }

    /**
     * 설명 : 계약 등록 요청의 업무 유효성을 검증한다.
     *
     * @param request 보험계약 등록 요청
     * @author hjKang
     * @since 2026-08-05
     */
    private void validateInput(ContractCreateRequest request) {
        // 1. 등록 요청 존재 확인
        if (request == null) {
            throw validationException(
                    "request",
                    "계약 등록 요청이 없습니다."
            );
        }

        // 2. 계약일이 서울 기준 미래 날짜인지 확인
        validateContractDate(request);
        // 3. 활성 보험회사와 계약일에 판매 가능한 상품인지 확인
        validateInsurerAndProduct(request);
        // 4. 계약일 기준 설계사의 자격과 소속 조직 확인
        validateAgentAndOrganization(request);
        // 5. 동일 보험회사에 같은 계약번호가 이미 등록되어 있는지 확인
        validateDuplicateContract(request);
    }

    /**
     * 설명 : 계약 수정 요청의 업무 유효성과 변경된 계약 식별값의 중복 여부를 검증한다.
     *
     * @param currentContract 수정 전 보험계약
     * @param request 보험계약 수정 요청
     * @author hjKang
     * @since 2026-08-05
     */
    private void validateInputForUpdate(InsuranceContract currentContract, ContractUpdateRequest request) {
        // 1. 계약일이 서울 기준 미래 날짜인지 확인
        validateContractDate(request);
        // 2. 활성 보험회사와 계약일에 판매 가능한 상품인지 확인
        validateInsurerAndProduct(request);
        // 3. 계약일 기준 설계사의 자격과 소속 조직 확인
        validateAgentAndOrganization(request);
        // 4. 보험회사·계약번호가 변경된 경우에만 중복 확인
        validateContractIdentityForUpdate(currentContract, request);
    }

    private void validateContractDate(ContractInput request) {
        if (request.getContractDate().isAfter(LocalDate.now(DateUtil.SEOUL_ZONE))) {
            throw new FgcBusinessException(
                    FgcErrorCode.CONT_002,
                    "contractDate",
                    Map.of(
                            "contractDate",
                            request.getContractDate()
                    ),
                    "계약일은 미래 날짜일 수 없습니다."
            );
        }
    }

    private void validateInsurerAndProduct(ContractInput request) {
        // 1. 보험회사가 존재하고 활성 상태인지 확인
        if (!contractQueryRepository.existsActiveInsurer(request.getInsurerId())) {
            throw validationException(
                    "insurerId",
                    "존재하지 않는 보험회사입니다."
            );
        }

        // 2. 해당 보험회사의 상품이며 계약일이 판매 가능 기간에 포함되는지 확인
        if (!contractQueryRepository.existsValidProductOffering(
                request.getInsurerId(),
                request.getProductOfferingId(),
                request.getContractDate()
        )) {
            throw validationException(
                    "productOfferingId",
                    "선택한 보험회사의 상품 판매 버전이 아닙니다."
            );
        }
    }

    private void validateAgentAndOrganization(ContractInput request) {
        // 1. 계약일 기준 활동 중인 FC 직급 설계사인지 확인
        if (!contractQueryRepository.existsEligibleAgent(
                request.getAgentId(),
                request.getContractDate()
        )) {
            throw validationException(
                    "agentId",
                    "계약일 기준 활동 중인 FC 직급의 모집설계사만 선택할 수 있습니다."
            );
        }

        // 2. 설계사의 소속 조직이 요청과 일치하고 계약일에 유효한지 확인
        if (!contractQueryRepository.existsValidAgentOrganization(
                request.getAgentId(),
                request.getOrganizationId(),
                request.getContractDate()
        )) {
            throw validationException(
                    "organizationId",
                    "설계사의 소속 조직이 일치하지 않습니다."
            );
        }
    }

    private void validateDuplicateContract(ContractInput request) {
        if (insuranceContractRepository.existsByInsurerIdAndContractNo(
                request.getInsurerId(),
                request.getContractNo()
        )) {
            throw new FgcBusinessException(
                    FgcErrorCode.CONT_001,
                    "contractNo",
                    Map.of(
                            "contractNo",
                            request.getContractNo()
                    ),
                    "저장 불가 — 이미 등록된 계약번호입니다."
            );
        }
    }

    /**
     * 설명 : 보험회사·계약번호가 변경된 경우 새 조합의 중복 여부를 검증한다.
     *
     * @param  currentContract : 기존 계약 데이터
     * @param  request : 변경할 계약 데이터
     * @author hjKang
     * @since 2026-08-06
     */
    private void validateContractIdentityForUpdate(InsuranceContract currentContract, ContractUpdateRequest request) {
        // 1. 계약 식별값인 보험회사·계약번호의 변경 여부 비교
        boolean insurerChanged =
                !Objects.equals(
                        currentContract.getInsurerId(),
                        request.getInsurerId()
                );

        boolean contractNoChanged =
                !Objects.equals(
                        currentContract.getContractNo(),
                        request.getContractNo()
                );

        // 2. 보험회사와 계약번호가 모두 같으면 자신의 기존 식별값이므로 중복 검사 생략
        if (!insurerChanged && !contractNoChanged) {
            return;
        }

        // 3. 변경된 보험회사·계약번호 조합이 이미 등록되어 있는지 확인
        if (insuranceContractRepository.existsByInsurerIdAndContractNo(
                request.getInsurerId(),
                request.getContractNo()
        )) {
            throw validationException(
                    "contractNo",
                    "저장 불가 — 이미 등록된 계약번호입니다."
            );
        }
    }

    private FgcBusinessException validationException(
            String field,
            String detail
    ) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }

    /**
     * 설명 : 지원하는 납입주기를 확인하고 직접 입력 보험료의 환산 코드를 반환한다.
     *
     * @param  paymentCycleCode 납입주기 코드
     * @return PremiumConversionRuleCode 환산 코드
     * @author hjKang
     * @since 2026-08-06
     */
    private PremiumConversionRuleCode determineConversionRuleCode(
            PaymentCycleCode paymentCycleCode
    ) {
        return switch (paymentCycleCode) {
            case MONTHLY, QUARTERLY, SEMI_ANNUAL, ANNUAL, SINGLE ->
                    PremiumConversionRuleCode.DIRECT_INPUT;

            case OTHER ->
                    throw new FgcBusinessException(
                            FgcErrorCode.COMMON_002,
                            "paymentCycleCode",
                            Map.of(
                                    "field", "paymentCycleCode",
                                    "paymentCycleCode", paymentCycleCode
                            ),
                            "화면에서 지원하지 않는 납입주기입니다."
                    );
        };
    }

    /**
     * 설명 : 검증된 계약 등록 요청을 저장할 엔티티로 변환한다.
     *
     * @param request 보험계약 등록 요청
     * @param conversionRuleCode 납입주기에 따라 결정한 보험료 환산 코드
     * @return 직접 입력 출처가 적용된 신규 보험계약 엔티티
     * @author hjKang
     * @since 2026-09-27
     */
    private InsuranceContract toContractEntity(
            ContractCreateRequest request,
            PremiumConversionRuleCode conversionRuleCode
    ) {
        return InsuranceContract.builder()
                .insurerId(request.getInsurerId())
                .productOfferingId(request.getProductOfferingId())
                .contractNo(request.getContractNo())
                .contractDate(request.getContractDate())
                .agentId(request.getAgentId())
                .organizationId(request.getOrganizationId())
                .premiumPerCycleAmount(request.getPremiumPerCycleAmount())
                .firstPremiumAmount(request.getFirstPremiumAmount())
                .monthlyEquivalentFirstPremium(request.getMonthlyEquivalentFirstPremium())
                .premiumConversionRuleCode(conversionRuleCode)
                .paymentCycleCode(request.getPaymentCycleCode())
                .paymentTermMonths(request.getPaymentTermMonths())
                .standardSurrenderDeductionAmount(request.getStandardSurrenderDeductionAmount())
                .currentStatus(request.getContractStatus())
                .dataOrigin(DataOrigin.MANUAL)
                .build();
    }

    /**
     * 설명 : 계약 생성 당시의 최초 상태사건 엔티티를 만든다.
     *
     * @param contractId 저장된 보험계약 ID
     * @param newStatus 계약 생성 당시 상태
     * @param effectiveAt 서울 기준 계약일 자정
     * @param receivedAt 상태사건 수신 시각
     * @return 순번과 수기 등록 출처가 적용된 최초 상태사건
     * @author hjKang
     * @since 2026-09-27
     */
    private ContractStatusEvent toInitialStatusEvent(
            Long contractId,
            ContractStatus newStatus,
            OffsetDateTime effectiveAt,
            OffsetDateTime receivedAt
    ) {
        return ContractStatusEvent.builder()
                .contractId(contractId)
                .eventSeq(1)
                .previousStatus(null)
                .newStatus(newStatus)
                .effectiveAt(effectiveAt)
                .receivedAt(receivedAt)
                .reasonCode("NEW_CONTRACT")
                .sourceSystem("FGC_MANUAL")
                .sourceEventKey("CONTRACT_CREATED:" + contractId)
                .dataOrigin(DataOrigin.MANUAL)
                .build();
    }

    private boolean hasScheduleImpactingChanges(
            InsuranceContract current,
            ContractInput updated
    ) {
        return !Objects.equals(current.getInsurerId(), updated.getInsurerId())
                || !Objects.equals(current.getProductOfferingId(), updated.getProductOfferingId())
                || !Objects.equals(current.getContractDate(), updated.getContractDate())
                || !Objects.equals(current.getAgentId(), updated.getAgentId())
                || !Objects.equals(current.getOrganizationId(), updated.getOrganizationId())
                || !Objects.equals(current.getPaymentCycleCode(), updated.getPaymentCycleCode())
                || moneyChanged(current.getFirstPremiumAmount(), updated.getFirstPremiumAmount())
                || moneyChanged(
                        current.getMonthlyEquivalentFirstPremium(),
                        updated.getMonthlyEquivalentFirstPremium()
                )
                || !Objects.equals(current.getPaymentTermMonths(), updated.getPaymentTermMonths())
                || moneyChanged(
                        current.getStandardSurrenderDeductionAmount(),
                        updated.getStandardSurrenderDeductionAmount()
                );
    }

    private boolean moneyChanged(BigDecimal current, BigDecimal updated) {
        if (current == null || updated == null) {
            return current != updated;
        }
        return current.compareTo(updated) != 0;
    }

    /**
     * 설명 : 활성 운영 스케줄이 있는 지급단계의 한도를 검증한다.
     * 생성·수정은 REALTIME, 사용자가 요청한 재검증은 MANUAL로 기록한다.
     *
     * @param contractId 보험계약 ID
     * @param checkKind 한도 검증 유형(REALTIME 또는 MANUAL)
     * @author hjKang
     * @since 2026-09-27
     */
    private void checkCapsForActiveSchedules(Long contractId, CapCheckKind checkKind) {
        // 1. 활성 운영 스케줄이 있는 지급단계만 검증 대상으로 선택
        for (PaymentStage paymentStage : PaymentStage.values()) {
            if (!scheduleService.hasActiveOperationalSchedule(contractId, paymentStage)) {
                continue;
            }

            // 2. 보험회사 → GA 단계에만 준법경영비 증빙 금액 반영
            BigDecimal complianceEvidenceAmount = paymentStage == PaymentStage.INSURER_TO_GA
                    ? contractQueryRepository.findComplianceEvidenceAmount(contractId, paymentStage)
                    : null;

            // 3. 요청한 검증 유형과 서울 기준 오늘 날짜로 계산 명령 생성
            CapCalculationCommand command = new CapCalculationCommand(
                    contractId,
                    paymentStage,
                    LocalDate.now(DateUtil.SEOUL_ZONE),
                    checkKind,
                    null,
                    complianceEvidenceAmount
            );

            // 4. 한도 판정 저장(룰셋이 없으면 검토 큐 등록)
            calculateCapCheckOrRegisterReview(contractId, paymentStage, command);
        }
    }

    private void calculateCapCheckOrRegisterReview(
            Long contractId,
            PaymentStage paymentStage,
            CapCalculationCommand command
    ) {
        // 1. 한도 판정 결과 계산 및 저장
        try {
            capCheckService.calculateAndSave(command);
        } catch (FgcBusinessException exception) {
            // 2. 적용 룰셋 없음(CAP_004) 이외의 업무 오류는 호출자에게 전달
            if (exception.getErrorCode() != FgcErrorCode.CAP_004) {
                throw exception;
            }
            // 3. 룰셋이 없으면 계약·지급단계를 검토 큐에 등록
            scheduleService.registerCapRuleReview(
                    contractId,
                    paymentStage,
                    exception.getDetail() == null
                            ? "적용 가능한 1,200% 룰셋이 없습니다."
                            : exception.getDetail()
            );
        }
    }

    /**
     * 설명 : 엔티티 변경의 영향을 받지 않는 감사 값을 복사한다.
     * 기존 감사 JSON의 필드를 유지하며, 수정 전 상태에만 DB의 수정 시각을 포함한다.
     *
     * @param contract 감사 대상 보험계약 엔티티
     * @param updatedAt 수정 전 DB 시각. 생성·수정 후 요청 스냅샷은 기존처럼 null
     * @return 감사 로그에 직렬화할 독립적인 값
     * @author hjKang
     * @since 2026-09-27
     */
    private Map<String, Object> toAuditSnapshot(
            InsuranceContract contract,
            OffsetDateTime updatedAt
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contractId", contract.getContractId());
        snapshot.put("insurerId", contract.getInsurerId());
        snapshot.put("productOfferingId", contract.getProductOfferingId());
        snapshot.put("contractNo", contract.getContractNo());
        snapshot.put("contractDate", contract.getContractDate());
        snapshot.put("agentId", contract.getAgentId());
        snapshot.put("organizationId", contract.getOrganizationId());
        snapshot.put("premiumPerCycleAmount", contract.getPremiumPerCycleAmount());
        snapshot.put("firstPremiumAmount", contract.getFirstPremiumAmount());
        snapshot.put("monthlyEquivalentFirstPremium", contract.getMonthlyEquivalentFirstPremium());
        snapshot.put("premiumConversionRuleCode", contract.getPremiumConversionRuleCode());
        snapshot.put("paymentCycleCode", contract.getPaymentCycleCode());
        snapshot.put("paymentTermMonths", contract.getPaymentTermMonths());
        snapshot.put("standardSurrenderDeductionAmount", contract.getStandardSurrenderDeductionAmount());
        snapshot.put("currentStatus", contract.getCurrentStatus());
        snapshot.put("dataOrigin", contract.getDataOrigin());
        // 기존 Mapper 조회와 요청 DTO 모두 createdBy를 설정하지 않았다.
        snapshot.put("createdBy", null);
        snapshot.put("updatedAt", updatedAt);
        return snapshot;
    }
}
