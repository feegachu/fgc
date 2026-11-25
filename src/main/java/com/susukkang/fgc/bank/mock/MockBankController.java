package com.susukkang.fgc.bank.mock;

import com.susukkang.fgc.bank.openbanking.OpenBankingProperties;
import com.susukkang.fgc.bank.service.BankLinkService;
import com.susukkang.fgc.common.util.DateUtil;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 설명 : 저장소에 들어 있는 모의 은행. 금융결제원 오픈뱅킹 이용기관 API 명세(v3.3.6)의 사용자 인증, 토큰 발급,
 * 사용자정보조회, 거래내역조회를 같은 주소와 같은 항목 이름으로 흉내 낸다. 실제 은행이 아니다.
 *
 * 개인은 금융결제원 테스트베드의 응답 자료 등록이 승인 대상이라 실제 연결을 시험할 수 없다(SRC-101).
 * 그래서 FGC의 은행 호출(OpenBankingClient)이 실제로 HTTP를 타고 가서 닿을 상대를 같이 넣었다.
 * FGC의 업무 API가 아니므로 FGC 로그인과 무관하게 자체 토큰으로만 확인하고, API 문서에는 싣지 않는다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Hidden
@RestController
@RequestMapping("/api/v1/mock-bank")
@ConditionalOnProperty(name = "fgc.openbanking.mock.enabled", havingValue = "true")
public class MockBankController {

    static final String USER_SEQ_NO = "1100000001";
    static final String FINTECH_USE_NUM = "199000000000000000000001";
    private static final String BANK_NAME = "모의은행";
    private static final String ACCOUNT_NUM_MASKED = "110-***-***001";
    private static final String ACCOUNT_HOLDER = "가상지에이";
    private static final String SCOPE = "login inquiry";
    // 명세의 항목 이름. 요청과 응답에 같은 이름을 쓴다.
    private static final String FIELD_CLIENT_ID = "client_id";
    private static final String FIELD_REDIRECT_URI = "redirect_uri";
    private static final String FIELD_SCOPE = "scope";
    private static final String FIELD_STATE = "state";
    private static final String FIELD_FINTECH_USE_NUM = "fintech_use_num";
    private static final String FIELD_BANK_TRAN_ID = "bank_tran_id";
    private static final String OPENING_BALANCE = "500000000";
    /** 명세: 한 쪽 최대 25건. */
    private static final int PAGE_SIZE = 25;
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final long TOKEN_SECONDS = 7_776_000L;
    private static final Pattern STATE = Pattern.compile("^[0-9A-Za-z]{32}$");
    private static final Pattern BANK_TRAN_ID = Pattern.compile("^[A-Z0-9]{10}U[A-Z0-9]{9}$");
    private static final Pattern TRAN_DTIME = Pattern.compile("^\\d{14}$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_TIME_MILLIS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CONSENT_STYLE = """
            body{margin:0;font-family:system-ui,-apple-system,"Malgun Gothic",sans-serif;background:#f3f5f9;color:#1b2333}
            main{max-width:26rem;margin:12vh auto;padding:2rem;background:#fff;border:1px solid #d9dee8;border-radius:.75rem}
            h1{margin:0 0 1rem;font-size:1.25rem}
            p{margin:.5rem 0;line-height:1.6}
            dl{margin:1rem 0;padding:1rem;background:#f3f5f9;border-radius:.5rem}
            dt{font-size:.8125rem;color:#5b6577}
            dd{margin:0 0 .5rem;font-weight:600}
            .actions{display:flex;gap:.5rem;margin-top:1.5rem}
            form{flex:1}
            button{width:100%;padding:.75rem;border-radius:.5rem;border:1px solid #1d4f91;font-size:1rem;cursor:pointer}
            .approve{background:#1d4f91;color:#fff}
            .deny{background:#fff;color:#1d4f91}
            """;

    private final OpenBankingProperties properties;
    private final MockBankLedger ledger;
    private final BankLinkService linkRequests;
    /** 인가 코드와 접근 토큰은 메모리에만 둔다. 서버를 다시 띄우면 모두 사라진다. */
    private final Map<String, Instant> codes = new ConcurrentHashMap<>();
    private final Map<String, Instant> accessTokens = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public MockBankController(OpenBankingProperties properties, MockBankLedger ledger, BankLinkService linkRequests) {
        this(properties, ledger, linkRequests, Clock.systemUTC());
    }

    MockBankController(OpenBankingProperties properties, MockBankLedger ledger, BankLinkService linkRequests, Clock clock) {
        this.properties = properties;
        this.ledger = ledger;
        this.linkRequests = linkRequests;
        this.clock = clock;
    }

    /** 사용자 인증: 동의 화면. 진입 전 오류는 명세대로 JSON 으로 준다. */
    @GetMapping("/oauth/2.0/authorize")
    public ResponseEntity<Object> authorize(@RequestParam Map<String, String> query, CsrfToken formToken) {
        if (!validAuthorization(query)) {
            return ResponseEntity.ok(rejected());
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(consentPage(query, formToken));
    }

    /** 동의 화면의 스타일. 운영 프록시의 보안 정책(style-src 'self')이 인라인 스타일을 막아 같은 출처의 파일로 준다. */
    @GetMapping(value = "/consent.css", produces = "text/css;charset=UTF-8")
    public String consentStyle() {
        return CONSENT_STYLE;
    }

    @PostMapping("/oauth/2.0/authorize/approve")
    public ResponseEntity<Object> approve(@RequestParam Map<String, String> query) {
        if (!validAuthorization(query)) {
            return ResponseEntity.ok(rejected());
        }
        String code = randomHex(16);
        Instant now = clock.instant();
        codes.values().removeIf(expiry -> !expiry.isAfter(now));
        codes.put(code, now.plus(CODE_TTL));
        return redirect("?code=" + code + "&scope=login%20inquiry&state=" + query.get(FIELD_STATE));
    }

    @PostMapping("/oauth/2.0/authorize/deny")
    public ResponseEntity<Object> deny(@RequestParam Map<String, String> query) {
        if (!validAuthorization(query)) {
            return ResponseEntity.ok(rejected());
        }
        return redirect("?error=access_denied&state=" + query.get(FIELD_STATE));
    }

    /** 토큰 발급. 명세대로 오류도 HTTP 200 에 rsp_code 로 준다. 인가 코드는 한 번 쓰이면 버린다. */
    @PostMapping(value = "/oauth/2.0/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> token(@RequestParam Map<String, String> form) {
        boolean valid = "authorization_code".equals(form.get("grant_type"))
                && properties.clientId().equals(form.get(FIELD_CLIENT_ID))
                && properties.clientSecret().equals(form.get("client_secret"))
                && properties.redirectUri().equals(form.get(FIELD_REDIRECT_URI));
        if (!valid || form.get("code") == null) {
            return rejected();
        }
        Instant now = clock.instant();
        Instant expiresAt = codes.remove(form.get("code"));
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            return rejected();
        }
        String accessToken = randomHex(24);
        accessTokens.values().removeIf(expiry -> !expiry.isAfter(now));
        accessTokens.put(accessToken, now.plusSeconds(TOKEN_SECONDS));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessToken);
        body.put("token_type", "Bearer");
        body.put("expires_in", TOKEN_SECONDS);
        body.put("refresh_token", randomHex(24));
        body.put(FIELD_SCOPE, SCOPE);
        body.put("user_seq_no", USER_SEQ_NO);
        return body;
    }

    /** 사용자정보조회: 이 사용자가 등록한 계좌. 계좌번호는 가린 값만 준다. */
    @GetMapping("/v2.0/user/me")
    public Map<String, Object> me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                  @RequestParam(value = "user_seq_no", required = false) String userSeqNo) {
        if (!authorized(authorization)) {
            return error("O0002", "Access Token 거부");
        }
        if (!USER_SEQ_NO.equals(userSeqNo)) {
            return error("A0004", "요청전문 포맷 에러");
        }
        Map<String, Object> account = new LinkedHashMap<>();
        account.put(FIELD_FINTECH_USE_NUM, FINTECH_USE_NUM);
        account.put("account_alias", "수수료 지급");
        account.put("bank_code_std", "097");
        account.put("bank_code_sub", "0000000");
        account.put("bank_name", BANK_NAME);
        account.put("savings_bank_name", "");
        account.put("account_num_masked", ACCOUNT_NUM_MASKED);
        account.put("account_seq", "");
        account.put("account_holder_name", ACCOUNT_HOLDER);
        account.put("account_holder_type", "P");
        account.put("account_type", "1");
        account.put("inquiry_agree_yn", "Y");
        account.put("inquiry_agree_dtime", DATE_TIME_MILLIS.format(now()).substring(0, 14));
        account.put("transfer_agree_yn", "N");
        account.put("transfer_agree_dtime", "");
        account.put("payer_num", "");
        Map<String, Object> body = success();
        body.put("user_seq_no", USER_SEQ_NO);
        body.put("user_ci", "MOCK-CI");
        body.put("user_name", ACCOUNT_HOLDER);
        body.put("res_cnt", "1");
        body.put("res_list", List.of(account));
        return body;
    }

    /** 거래내역조회(핀테크이용번호). 값은 금액과 건수까지 모두 문자열로 준다. */
    @GetMapping("/v2.0/account/transaction_list/fin_num")
    public Map<String, Object> transactions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam Map<String, String> query) {
        if (!authorized(authorization)) {
            return error("O0002", "Access Token 거부");
        }
        LocalDate from = date(query.get("from_date"));
        LocalDate to = date(query.get("to_date"));
        Integer offset = offset(query.get("befor_inquiry_trace_info"));
        boolean wellFormed = query.get(FIELD_BANK_TRAN_ID) != null
                && BANK_TRAN_ID.matcher(query.get(FIELD_BANK_TRAN_ID)).matches()
                && query.get(FIELD_FINTECH_USE_NUM) != null
                && Set.of("A", "I", "O").contains(String.valueOf(query.get("inquiry_type")))
                && "D".equals(query.get("inquiry_base"))
                && from != null && to != null && !from.isAfter(to)
                && Set.of("D", "A").contains(String.valueOf(query.get("sort_order")))
                && query.get("tran_dtime") != null && TRAN_DTIME.matcher(query.get("tran_dtime")).matches()
                && offset != null;
        if (!wellFormed) {
            return error("A0004", "요청전문 포맷 에러");
        }
        if (!FINTECH_USE_NUM.equals(query.get(FIELD_FINTECH_USE_NUM))) {
            return error("A0304", "핀테크이용번호 정보 불일치");
        }
        // 이 계좌에는 출금만 있다. 입금만 물으면 빈 목록이다.
        List<MockBankLedger.Row> rows = "I".equals(query.get("inquiry_type"))
                ? List.of() : new ArrayList<>(ledger.withdrawals(from, to));
        String balance = rows.isEmpty() ? OPENING_BALANCE : rows.get(rows.size() - 1).afterBalance().toPlainString();
        if ("D".equals(query.get("sort_order"))) {
            Collections.reverse(rows);
        }
        int end = Math.min(offset + PAGE_SIZE, rows.size());
        List<Map<String, Object>> page = new ArrayList<>();
        for (MockBankLedger.Row row : rows.subList(Math.min(offset, rows.size()), end)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tran_date", DATE.format(row.tranDate()));
            item.put("tran_time", row.tranTime());
            item.put("inout_type", "출금");
            item.put("tran_type", "대체");
            item.put("print_content", row.printContent());
            item.put("tran_amt", row.amount().toPlainString());
            item.put("after_balance_amt", row.afterBalance().toPlainString());
            item.put("branch_name", "모의지점");
            page.add(item);
        }
        boolean more = end < rows.size();
        Map<String, Object> body = success();
        body.put(FIELD_BANK_TRAN_ID, query.get(FIELD_BANK_TRAN_ID));
        body.put("bank_tran_date", DATE.format(now().toLocalDate()));
        body.put("bank_code_tran", "097");
        body.put("bank_rsp_code", "000");
        body.put("bank_rsp_message", "");
        body.put("bank_name", BANK_NAME);
        body.put("savings_bank_name", "");
        body.put(FIELD_FINTECH_USE_NUM, FINTECH_USE_NUM);
        body.put("balance_amt", balance);
        body.put("page_record_cnt", String.valueOf(page.size()));
        body.put("next_page_yn", more ? "Y" : "N");
        // 다음 쪽을 물을 때 그대로 돌려받는 값(AN 20). 여기서는 다음 쪽의 시작 위치다.
        body.put("befor_inquiry_trace_info", more ? "%020d".formatted(end) : "");
        body.put("res_list", page);
        return body;
    }

    private static Map<String, Object> rejected() {
        return error("O0001", "인증요청 거부-인증 파라미터 오류");
    }

    private boolean validAuthorization(Map<String, String> query) {
        return "code".equals(query.get("response_type"))
                && properties.clientId().equals(query.get(FIELD_CLIENT_ID))
                && properties.redirectUri().equals(query.get(FIELD_REDIRECT_URI))
                && SCOPE.equals(query.get(FIELD_SCOPE))
                && query.get(FIELD_STATE) != null && STATE.matcher(query.get(FIELD_STATE)).matches()
                && query.get("auth_type") != null
                // 모의 은행에는 은행 로그인이 없다. 처리 권한자가 FGC에서 방금 낸 연결 요청인지가 그 자리를 대신한다.
                // 이 확인이 없으면 로그인하지 않은 사람이 코드와 토큰을 받아 확정 지급액을 읽을 수 있다.
                && linkRequests.isPending(query.get(FIELD_STATE));
    }

    private boolean authorized(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        Instant expiry = accessTokens.get(authorization.substring("Bearer ".length()));
        return expiry != null && expiry.isAfter(clock.instant());
    }

    private ResponseEntity<Object> redirect(String query) {
        return ResponseEntity.status(302).location(URI.create(properties.redirectUri() + query)).build();
    }

    private static Map<String, Object> success() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_tran_id", randomHex(16));
        body.put("api_tran_dtm", DATE_TIME_MILLIS.format(now()));
        body.put("rsp_code", "A0000");
        body.put("rsp_message", "");
        return body;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rsp_code", code);
        body.put("rsp_message", message);
        return body;
    }

    private static LocalDate date(String value) {
        try {
            return value == null ? null : LocalDate.parse(value, DATE);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    /** 없으면 첫 쪽(0), 형식이 틀리면 null. */
    private static Integer offset(String trace) {
        if (trace == null || trace.isBlank()) {
            return 0;
        }
        if (!trace.matches("\\d{1,20}")) {
            return null;
        }
        try {
            return Math.toIntExact(new BigInteger(trace).longValueExact());
        } catch (ArithmeticException tooLarge) {
            return null;
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(DateUtil.SEOUL_ZONE);
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    /** 값은 모두 검증을 거쳤지만 화면에 넣기 전에 한 번 더 이스케이프한다. */
    private String consentPage(Map<String, String> query, CsrfToken formToken) {
        // 폼 위조 방지 값. 같은 값이 쿠키에도 실려 있고, 폼이 돌아올 때 보안 체인이 둘을 대조한다.
        StringBuilder hidden = new StringBuilder("<input type=\"hidden\" name=\"")
                .append(formToken.getParameterName()).append("\" value=\"")
                .append(HtmlUtils.htmlEscape(formToken.getToken())).append("\">");
        for (String name : List.of("response_type", FIELD_CLIENT_ID, FIELD_REDIRECT_URI, FIELD_SCOPE, FIELD_STATE, "auth_type")) {
            hidden.append("<input type=\"hidden\" name=\"").append(name).append("\" value=\"")
                    .append(HtmlUtils.htmlEscape(query.get(name))).append("\">");
        }
        return """
                <!doctype html>
                <html lang="ko">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>모의 은행 계좌 조회 동의</title>
                <link rel="stylesheet" href="/api/v1/mock-bank/consent.css">
                </head>
                <body>
                <main>
                <h1>모의 은행 계좌 조회 동의</h1>
                <p>FGC가 아래 계좌의 거래내역을 조회하려고 합니다. 조회만 하며 이체는 하지 않습니다.</p>
                <p>이 화면은 실제 은행이 아닙니다. 연습용으로 만든 자료만 있습니다.</p>
                <dl>
                <dt>은행</dt><dd>%s</dd>
                <dt>계좌</dt><dd>%s</dd>
                <dt>예금주</dt><dd>%s</dd>
                </dl>
                <div class="actions">
                <form method="post" action="/api/v1/mock-bank/oauth/2.0/authorize/deny">%s<button class="deny" type="submit">취소</button></form>
                <form method="post" action="/api/v1/mock-bank/oauth/2.0/authorize/approve">%s<button class="approve" type="submit">동의</button></form>
                </div>
                </main>
                </body>
                </html>
                """.formatted(BANK_NAME, ACCOUNT_NUM_MASKED, ACCOUNT_HOLDER, hidden, hidden);
    }
}
