package com.susukkang.fgc.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인터페이스정의서 3-3 규칙 3 — MPA 는 오류 화면, Ajax 는 JSON 봉투.
 *
 * MockMvc 로는 못 잡는다. 서블릿 컨테이너의 ERROR 디스패치(/error)를 타야 오류 화면이 뜨는데
 * MockMvc 는 그 디스패치를 하지 않는다. 그래서 실제 포트를 띄운다.
 *
 * 이 테스트가 지키는 것 — GlobalExceptionHandler 의 annotations = RestController.class 제한.
 * 그 제한이 풀리면 이 advice 가 MPA 예외까지 JSON 으로 내려보내 error/403·404·500.html 이
 * 영원히 렌더링되지 않는다(실제로 브라우저에서 없는 경로를 열면 오류 화면 대신 JSON 이 보였다).
 *
 * /assets/** 를 쓰는 이유 — SecurityConfig 에서 permitAll 이라 로그인 없이 404 까지 갈 수 있다.
 * 인증이 필요한 경로는 404 이전에 /login 으로 302 된다.
 *
 * TestRestTemplate 을 안 쓰는 이유 — 기본 JDK 커넥션이 404 + chunked 응답 본문에서 EOF 로 깨진다.
 * java.net.http 는 표준 라이브러리이고 이 문제가 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MpaErrorPageIntegrationTest {

    @LocalServerPort
    int port;

    private HttpResponse<String> getHtml(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Accept", "text/html")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 500 화면의 문구는 messages.properties 의 error.common.internal 하나에서 온다
     * (인터페이스정의서 3-3 SIR-007 규칙 3·4 — 화면과 JSON 이 같은 키를 쓴다).
     * 템플릿이 #messages.msg + #strings.replace 로 {requestId} 를 푸는데, 이 표현식이 깨지면
     * 화면이 렌더링 도중 죽는다. 실제로 그려 봐야만 알 수 있다.
     *
     * /error 를 직접 부르면 BasicErrorController 가 오류 속성 없이 500 을 낸다 — 강제로
     * 예외를 만드는 장치 없이 500 화면을 그려 볼 수 있는 가장 싼 방법이다.
     */
    @Test
    void internal_error_page_uses_message_key_not_hardcoded_text() throws Exception {
        HttpResponse<String> response = getHtml("/error");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body())
                .contains("500 · 처리 오류")
                .contains("처리 중 오류가 발생했습니다.")      // error.common.internal 이 실제로 풀렸다
                .contains("담당자에게 알려주세요")
                // 자리표시자가 그대로 남아 있으면 치환이 안 된 것이다.
                // 템플릿 주석에도 {requestId} 가 나오므로 문장째로 본다.
                .doesNotContain("요청번호 {requestId}")
                .contains("수수료 정산·검증 Workspace");         // 셸까지 끝까지 렌더링됨
    }

    /**
     * FUN-002(#82) 인수조건 — 권한 없는 화면 URL 직접 호출은 403 + error/403.html 렌더링.
     *
     * SecurityConfig 의 GET /audit-logs 규칙은 필터 단계에서 sendError(403)로 거부하고,
     * 그 뒤의 ERROR 디스패치(/error → error/403.html)는 MockMvc 가 타지 않아 실제 포트로 본다.
     * 시드(V3)의 settle01(SETTLEMENT)은 AUDT-W01(COMPLIANCE·SYSTEM_ADMIN 전용)을 못 본다.
     *
     * 로그인이 audit_log 에 LOGIN_SUCCESS 한 행을 남긴다 — 실제 로그인과 같은 경로라 무해하고,
     * RANDOM_PORT 는 서버 스레드가 따로 돌아 @Transactional 롤백으로 지울 수도 없다.
     */
    @Test
    void forbidden_screen_url_renders_403_page_not_menu_hiding() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String base = "http://localhost:" + port;

        // 1) 로그인 화면에서 CSRF 토큰을 꺼낸다 (세션 쿠키는 CookieManager 가 들고 간다)
        HttpResponse<String> loginPage = client.send(
                HttpRequest.newBuilder().uri(URI.create(base + "/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPage.body());
        assertThat(csrf.find()).as("로그인 폼의 _csrf hidden input").isTrue();

        // 2) settle01 로 폼 로그인 — 성공하면 defaultSuccessUrl("/") 로 302
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder().uri(URI.create(base + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "username=settle01&password=" + URLEncoder.encode("fgc1234!", UTF_8)
                                        + "&_csrf=" + URLEncoder.encode(csrf.group(1), UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElse(""))
                .as("로그인 성공 리다이렉트 — /login?error 면 자격증명·CSRF 문제")
                .endsWith("/");

        // 3) 감사로그 화면 직접 호출 — 메뉴 숨김이 아니라 서버가 403 화면으로 막아야 한다
        HttpResponse<String> response = getHtmlWith(client, "/audit-logs");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(response.body())
                .contains("403 · 권한 없음")                       // error/403.html
                .contains("이 작업을 할 권한이 없습니다.")            // error.auth.forbidden 이 실제로 풀렸다
                .contains("수수료 정산·검증 Workspace");             // 셸 레이아웃까지 렌더링됨
    }

    private HttpResponse<String> getHtmlWith(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Accept", "text/html")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void missing_path_renders_error_page_instead_of_json_envelope() throws Exception {
        HttpResponse<String> response = getHtml("/assets/no-such-file.css");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(response.body())
                .contains("404 · 찾을 수 없음")                    // error/404.html
                .contains("수수료 정산·검증 Workspace")              // 셸 레이아웃까지 렌더링됨
                .doesNotContain("FGC-COMMON-004");                // JSON 봉투가 아니다
    }
}
