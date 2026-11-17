package com.susukkang.fgc.bank.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.bank.dto.BankReconciliationResponse;
import com.susukkang.fgc.bank.openbanking.BankWithdrawal;
import com.susukkang.fgc.bank.openbanking.OpenBankingClient;
import com.susukkang.fgc.bank.openbanking.OpenBankingException;
import com.susukkang.fgc.bank.repository.BankReconciliationRepository;
import com.susukkang.fgc.bank.repository.BankReconciliationRepository.FinalizedRun;
import com.susukkang.fgc.bank.service.BankLinkService.Session;
import com.susukkang.fgc.bank.service.BankTransferMatcher.Expected;
import com.susukkang.fgc.bank.service.BankTransferMatcher.Matched;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 설명 : FUN-090 지급 이체 대사. 확정된 정산월의 설계사별 확정 지급 순액과, 연결한 계좌에서 실제로 나간 출금을 맞춘다.
 *
 * - 은행에서는 조회만 한다. 이체는 부르지 않는다(COR-001).
 * - 확정된 달만 대사한다. 이체는 확정 뒤에 한다고 보고, 확정일부터 31일(오늘을 넘지 않음) 사이의 출금을 읽는다.
 * - 어긋난 건을 예외함에 넣지 않는다. 이 화면의 결과로만 남긴다.
 * - 은행 호출은 DB 트랜잭션 밖에서 한다. 은행이 실패하면 결과를 남기지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Service
public class BankReconciliationService {

    /** 확정일부터 며칠 뒤까지의 출금을 볼지. 가상 GA 정책이다(운영정책서 제36조의2). */
    static final int INQUIRY_DAYS = 31;
    /** 접근 토큰 거부·만료. 다시 연결해야 한다. */
    private static final Set<String> TOKEN_REJECTED = Set.of("O0002", "O0003");

    private final BankReconciliationRepository repository;
    private final BankLinkService linkService;
    private final OpenBankingClient client;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    public BankReconciliationService(BankReconciliationRepository repository, BankLinkService linkService,
                                     OpenBankingClient client, AuditLogService auditLogService,
                                     ApplicationEventPublisher events, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.linkService = linkService;
        this.client = client;
        this.auditLogService = auditLogService;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 은행에서 출금 내역을 읽어 그 달의 확정 지급과 맞추고 결과를 남긴다. */
    public BankReconciliationResponse run(Long userId, String settlementMonth) {
        LocalDate month = parse(settlementMonth);
        FinalizedRun finalized = repository.findFinalizedRun(month)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.BANK_002));
        Session session = linkService.requireSession();
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        LocalDate from = finalized.finalizedOn();
        LocalDate to = from.plusDays(INQUIRY_DAYS).isAfter(today) ? today : from.plusDays(INQUIRY_DAYS);

        // 모의 은행은 이 알림을 받아 그 달의 이체를 준비한다. 실제 은행에 붙였을 때는 받는 곳이 없다.
        events.publishEvent(new BankInquiryStarting(month));
        List<BankWithdrawal> withdrawals;
        try {
            withdrawals = client.withdrawals(session.token(), session.link().fintechUseNum(), from, to);
        } catch (OpenBankingException failure) {
            if (TOKEN_REJECTED.contains(failure.code())) {
                linkService.disconnect(session.token());
                throw new FgcBusinessException(FgcErrorCode.BANK_001);
            }
            throw new FgcBusinessException(FgcErrorCode.BANK_003, Map.of("bankCode", failure.code()));
        }

        Long runId = transaction.execute(status -> {
            List<Expected> expected = repository.findExpectedTransfers(month).stream()
                    .map(transfer -> new Expected(transfer.agentId(), transfer.amount())).toList();
            List<Matched> items = BankTransferMatcher.match(month, expected, withdrawals);
            Long id = repository.insertRun(month, finalized.validationRunId(), from, to, session.link(),
                    withdrawals.size(), userId);
            for (Matched item : items) {
                repository.insertItem(id, item);
            }
            auditLogService.record(AuditLogService.AuditEvent.builder()
                    .actionCode("BANK_RECONCILIATION_RUN")
                    .entityType("BANK_RECONCILIATION_RUN")
                    .entityId(String.valueOf(id))
                    .userId(userId)
                    .after(Map.of("settlementMonth", DateUtil.formatSettlementMonth(month),
                            "inquiryFrom", from.toString(), "inquiryTo", to.toString(),
                            "withdrawalCount", withdrawals.size(),
                            "mismatchCount", items.stream()
                                    .filter(item -> !BankTransferMatcher.MATCHED.equals(item.result())).count()))
                    .reason("지급 이체 대사 실행")
                    .build());
            return id;
        });
        return repository.findById(runId).orElseThrow();
    }

    /** 그 달에 마지막으로 돌린 대사 결과. 돌린 적이 없으면 null 이다. */
    public BankReconciliationResponse latest(String settlementMonth) {
        return repository.findLatest(parse(settlementMonth)).orElse(null);
    }

    private static LocalDate parse(String settlementMonth) {
        try {
            return DateUtil.parseSettlementMonth(settlementMonth);
        } catch (DateTimeException | NullPointerException invalid) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "settlementMonth",
                    Map.of("field", "settlementMonth"), null);
        }
    }
}
