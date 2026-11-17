package com.susukkang.fgc.bank.mock;

import com.susukkang.fgc.bank.openbanking.OpenBankingProperties;
import com.susukkang.fgc.common.util.DateUtil;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fgc.openbanking.mock.enabled", havingValue = "true")
public class MockBankController {

    static final String USER_SEQ_NO = "1100000001";
    static final String FINTECH_USE_NUM = "199000000000000000000001";
    private static final String BANK_NAME = "모의은행";
    private static final String ACCOUNT_NUM_MASKED = "110-***-***001";
    private static final String ACCOUNT_HOLDER = "가상지에이";
    private static final String SCOPE = "login inquiry";
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

    private final OpenBankingProperties properties;
    private final MockBankLedger ledger;
    /** 인가 코드와 접근 토큰은 메모리에만 둔다. 서버를 다시 띄우면 모두 사라진다. */
    private final Map<String, Instant> codes = new ConcurrentHashMap<>();
    private final Set<String> accessTokens = ConcurrentHashMap.newKeySet();

    /** 사용자 인증: 동의 화면. 진입 전 오류는 명세대로 JSON 으로 준다. */
    @GetMapping("/oauth/2.0/authorize")
    public ResponseEntity<Object> authorize(@RequestParam Map<String, String> query) {
        if (!validAuthorization(query)) {
            return ResponseEntity.ok(error("O0001", "인증요청 거부-인증 파라미터 오류"));
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(consentPage(query));
    }

    @PostMapping("/oauth/2.0/authorize/approve")
    public ResponseEntity<Object> approve(@RequestParam Map<String, String> query) {
        if (!validAuthorization(query)) {
            return ResponseEntity.ok(error("O0001", "인증요청 거부-인증 파라미터 오류"));
        }
        String code = randomHex(16);
        codes.put(code, Instant.now().plus(CODE_TTL));
        return redirect("?code=" + code + "&scope=login%20inquiry&state=" + query.get("state"));
    }

    @PostMapping("/oauth/2.0/authorize/deny")
    public ResponseEntity<Object> deny(@RequestParam Map<String, String> query) {
        if (!validAuthorization(query)) {
            return ResponseEntity.ok(error("O0001", "인증요청 거부-인증 파라미터 오류"));
        }
        return redirect("?error=access_denied&state=" + query.get("state"));
    }

    /** 토큰 발급. 명세대로 오류도 HTTP 200 에 rsp_code 로 준다. 인가 코드는 한 번 쓰이면 버린다. */
    @PostMapping(value = "/oauth/2.0/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> token(@RequestParam Map<String, String> form) {
        Instant expiresAt = form.get("code") == null ? null : codes.remove(form.get("code"));
        boolean valid = "authorization_code".equals(form.get("grant_type"))
                && properties.clientId().equals(form.get("client_id"))
                && properties.clientSecret().equals(form.get("client_secret"))
                && properties.redirectUri().equals(form.get("redirect_uri"))
                && expiresAt != null && expiresAt.isAfter(Instant.now());
        if (!valid) {
            return error("O0001", "인증요청 거부-인증 파라미터 오류");
        }
        String accessToken = randomHex(24);
        accessTokens.add(accessToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessToken);
        body.put("token_type", "Bearer");
        body.put("expires_in", TOKEN_SECONDS);
        body.put("refresh_token", randomHex(24));
        body.put("scope", SCOPE);
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
        account.put("fintech_use_num", FINTECH_USE_NUM);
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
        boolean wellFormed = query.get("bank_tran_id") != null && BANK_TRAN_ID.matcher(query.get("bank_tran_id")).matches()
                && query.get("fintech_use_num") != null
                && Set.of("A", "I", "O").contains(String.valueOf(query.get("inquiry_type")))
                && "D".equals(query.get("inquiry_base"))
                && from != null && to != null && !from.isAfter(to)
                && Set.of("D", "A").contains(String.valueOf(query.get("sort_order")))
                && query.get("tran_dtime") != null && TRAN_DTIME.matcher(query.get("tran_dtime")).matches()
                && offset != null;
        if (!wellFormed) {
            return error("A0004", "요청전문 포맷 에러");
        }
        if (!FINTECH_USE_NUM.equals(query.get("fintech_use_num"))) {
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
        body.put("bank_tran_id", query.get("bank_tran_id"));
        body.put("bank_tran_date", DATE.format(now().toLocalDate()));
        body.put("bank_code_tran", "097");
        body.put("bank_rsp_code", "000");
        body.put("bank_rsp_message", "");
        body.put("bank_name", BANK_NAME);
        body.put("savings_bank_name", "");
        body.put("fintech_use_num", FINTECH_USE_NUM);
        body.put("balance_amt", balance);
        body.put("page_record_cnt", String.valueOf(page.size()));
        body.put("next_page_yn", more ? "Y" : "N");
        // 다음 쪽을 물을 때 그대로 돌려받는 값(AN 20). 여기서는 다음 쪽의 시작 위치다.
        body.put("befor_inquiry_trace_info", more ? "%020d".formatted(end) : "");
        body.put("res_list", page);
        return body;
    }

    private boolean validAuthorization(Map<String, String> query) {
        return "code".equals(query.get("response_type"))
                && properties.clientId().equals(query.get("client_id"))
                && properties.redirectUri().equals(query.get("redirect_uri"))
                && SCOPE.equals(query.get("scope"))
                && query.get("state") != null && STATE.matcher(query.get("state")).matches()
                && query.get("auth_type") != null;
    }

    private boolean authorized(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                && accessTokens.contains(authorization.substring("Bearer ".length()));
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
    private String consentPage(Map<String, String> query) {
        StringBuilder hidden = new StringBuilder();
        for (String name : List.of("response_type", "client_id", "redirect_uri", "scope", "state", "auth_type")) {
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
                <style>
                body{margin:0;font-family:system-ui,-apple-system,"Malgun Gothic",sans-serif;background:#f3f5f9;color:#1b2333}
                main{max-width:26rem;margin:12vh auto;padding:2rem;background:#fff;border:1px solid #d9dee8;border-radius:.75rem}
                h1{margin:0 0 1rem;font-size:1.25rem}
                p{margin:.5rem 0;line-height:1.6}
                dl{margin:1rem 0;padding:1rem;background:#f3f5f9;border-radius:.5rem}
                dt{font-size:.8125rem;color:#5b6577}
                dd{margin:0 0 .5rem;font-weight:600}
                .actions{display:flex;gap:.5rem;margin-top:1.5rem}
                form{flex:1}
                button{width:100%%;padding:.75rem;border-radius:.5rem;border:1px solid #1d4f91;font-size:1rem;cursor:pointer}
                .approve{background:#1d4f91;color:#fff}
                .deny{background:#fff;color:#1d4f91}
                </style>
                </head>
                <body>
                <main>
                <h1>모의 은행 계좌 조회 동의</h1>
                <p>FGC가 아래 계좌의 거래내역을 조회하려고 합니다. 조회만 하며 이체는 하지 않습니다.</p>
                <p>이 화면은 실제 은행이 아닙니다. 시연용 자료만 있습니다.</p>
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
