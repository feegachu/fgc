package com.susukkang.fgc.bank.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.bank.dto.BankAuthorizeUrlResponse;
import com.susukkang.fgc.bank.dto.BankLinkResponse;
import com.susukkang.fgc.bank.openbanking.BankAccount;
import com.susukkang.fgc.bank.openbanking.BankToken;
import com.susukkang.fgc.bank.openbanking.OpenBankingClient;
import com.susukkang.fgc.bank.openbanking.OpenBankingException;
import com.susukkang.fgc.bank.openbanking.OpenBankingProperties;
import com.susukkang.fgc.bank.repository.BankLinkRepository;
import com.susukkang.fgc.bank.repository.BankLinkRepository.Link;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 설명 : FUN-090 수수료 지급 계좌 연결. 오픈뱅킹 사용자 인증(3-legged)으로 조회 동의를 받는다.
 *
 * - 동의 화면으로 보낼 때 만든 state 는 요청한 사용자에게 묶어 서버 메모리에 10분만 둔다. 한 번 쓰면 버린다.
 * - 토큰은 DB에 저장하지 않고 서버 메모리에만 둔다. 서버를 다시 띄우면 다시 연결해야 한다.
 *   대사는 한 달에 한 번 하는 일이라, 토큰을 저장해 지키는 것보다 저장하지 않는 쪽을 골랐다.
 * - DB에는 핀테크이용번호와 은행이 가려서 준 계좌번호만 남긴다. 은행 호출은 DB 트랜잭션 밖에서 한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Service
public class BankLinkService {

    static final Duration AUTHORIZATION_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OpenBankingClient client;
    private final OpenBankingProperties properties;
    private final BankLinkRepository links;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transaction;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** 연결한 계좌와 그 계좌의 토큰은 한 묶음으로만 바뀐다. 다른 연결의 토큰으로 이 계좌를 묻는 일이 없게 한다. */
    private final AtomicReference<Connected> connected = new AtomicReference<>();

    /** 동의 화면으로 보낸 요청. 돌아왔을 때 같은 사용자의 같은 요청인지 본다. */
    private record Pending(Long userId, Instant expiresAt) {
    }

    private record Connected(String fintechUseNum, BankToken token, Instant expiresAt) {
    }

    /** 은행을 부를 수 있는 상태: 연결한 계좌와 살아 있는 토큰. */
    record Session(Link link, BankToken token) {
    }

    public BankLinkService(OpenBankingClient client, OpenBankingProperties properties, BankLinkRepository links,
                           AuditLogService auditLogService, PlatformTransactionManager transactionManager) {
        this.client = client;
        this.properties = properties;
        this.links = links;
        this.auditLogService = auditLogService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public BankLinkResponse status() {
        boolean mock = properties.mock() != null && properties.mock().enabled();
        return links.find()
                .map(link -> new BankLinkResponse(true, liveTokenFor(link).isPresent(), link.bankName(),
                        link.accountNumMasked(), link.accountHolderName(), link.linkedAt(), mock))
                .orElseGet(() -> new BankLinkResponse(false, false, null, null, null, null, mock));
    }

    /** 은행 동의 화면 주소를 만든다. 주소에 넣은 state 를 이 사용자에게 묶어 둔다. */
    public BankAuthorizeUrlResponse authorizeUrl(Long userId) {
        Instant now = Instant.now();
        pending.values().removeIf(request -> request.expiresAt().isBefore(now));
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String state = HexFormat.of().formatHex(random);
        pending.put(state, new Pending(userId, now.plus(AUTHORIZATION_TTL)));
        return new BankAuthorizeUrlResponse(client.authorizeUrl(state));
    }

    /** 동의 뒤 은행이 돌려준 코드로 토큰을 받고, 조회에 동의한 첫 계좌를 수수료 지급 계좌로 연결한다. */
    public BankLinkResponse link(Long userId, String code, String state) {
        // 누가 시도했든 한 번 쓰인 state 는 버린다.
        Pending requested = pending.remove(state);
        if (requested == null || !requested.userId().equals(userId) || requested.expiresAt().isBefore(Instant.now())) {
            throw new FgcBusinessException(FgcErrorCode.BANK_004);
        }
        BankToken token;
        Optional<BankAccount> agreed;
        try {
            token = client.exchange(code);
            // shortcut: 조회에 동의한 계좌가 여럿이어도 첫 계좌를 쓴다. 계좌를 고르는 화면이 필요해지면 그때 만든다.
            agreed = client.accounts(token).stream().filter(BankAccount::inquiryAgreed).findFirst();
        } catch (OpenBankingException failure) {
            throw new FgcBusinessException(FgcErrorCode.BANK_003, Map.of("bankCode", failure.code()));
        }
        BankAccount account = agreed.orElseThrow(() -> new FgcBusinessException(FgcErrorCode.BANK_005));
        // 계좌 저장과 토큰 교체를 한 번에 한 연결씩 한다. 두 사람이 동시에 연결해도 나중에 끝난 쪽의 계좌와 토큰이 함께 남는다.
        synchronized (connected) {
            saveLink(userId, account);
            connected.set(new Connected(account.fintechUseNum(), token,
                    Instant.now().plusSeconds(token.expiresInSeconds())));
        }
        return status();
    }

    /** 지금 동의 화면에 가 있는 연결 요청인지. 모의 은행이 FGC가 낸 요청으로만 동의 화면을 열도록 묻는다. */
    public boolean isPending(String state) {
        Pending requested = pending.get(state);
        return requested != null && requested.expiresAt().isAfter(Instant.now());
    }

    private void saveLink(Long userId, BankAccount account) {
        transaction.executeWithoutResult(status -> {
            Optional<Link> before = links.find();
            links.save(account.fintechUseNum(), account.bankName(), account.accountNumMasked(),
                    account.accountHolderName(), userId);
            auditLogService.record(AuditLogService.AuditEvent.builder()
                    .actionCode("BANK_ACCOUNT_LINKED")
                    .entityType("BANK_ACCOUNT_LINK")
                    .entityId("1")
                    .userId(userId)
                    .before(before.map(link -> auditValue(link.bankName(), link.accountNumMasked())).orElse(null))
                    .after(auditValue(account.bankName(), account.accountNumMasked()))
                    .reason("수수료 지급 계좌 연결")
                    .build());
        });
    }

    /** 연결한 계좌와 살아 있는 토큰을 준다. 없으면 다시 연결하라고 알린다. */
    Session requireSession() {
        Link link = links.find().orElseThrow(() -> new FgcBusinessException(FgcErrorCode.BANK_001));
        BankToken token = liveTokenFor(link).orElseThrow(() -> new FgcBusinessException(FgcErrorCode.BANK_001));
        return new Session(link, token);
    }

    /**
     * 은행이 이 토큰을 거절했을 때 버린다. 계좌 연결 정보는 남긴다.
     * 그사이 다른 요청이 다시 연결했으면 그 새 연결은 건드리지 않는다.
     */
    void disconnect(BankToken rejected) {
        connected.updateAndGet(current -> current != null && current.token().equals(rejected) ? null : current);
    }

    /** 이 계좌로 받은 살아 있는 토큰. 다른 계좌의 토큰은 주지 않는다. */
    private Optional<BankToken> liveTokenFor(Link link) {
        Connected current = connected.get();
        return current != null && current.fintechUseNum().equals(link.fintechUseNum())
                && current.expiresAt().isAfter(Instant.now())
                ? Optional.of(current.token()) : Optional.empty();
    }

    /** 감사로그에는 은행 이름과 가린 계좌번호만 남긴다. 핀테크이용번호와 토큰은 남기지 않는다. */
    private static Map<String, Object> auditValue(String bankName, String accountNumMasked) {
        return Map.of("bankName", bankName, "accountNumMasked", accountNumMasked);
    }
}
