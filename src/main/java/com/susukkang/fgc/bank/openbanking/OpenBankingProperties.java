package com.susukkang.fgc.bank.openbanking;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 설명 : 오픈뱅킹 연결 설정(fgc.openbanking.*). 기본값은 저장소에 들어 있는 모의 은행을 가리킨다.
 *
 * @param baseUrl       서버가 토큰·계좌·거래내역을 부르는 주소
 * @param authorizeUrl  사용자의 브라우저가 은행 동의 화면으로 가는 주소
 * @param redirectUri   동의 뒤 은행이 브라우저를 돌려보내는 주소(화면 경로)
 * @param clientUseCode 거래고유번호 앞에 붙는 이용기관코드 10자리
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@ConfigurationProperties("fgc.openbanking")
public record OpenBankingProperties(String baseUrl, String authorizeUrl, String clientId, String clientSecret,
                                    String redirectUri, String clientUseCode, Duration timeout, Mock mock) {

    /** enabled 가 true 면 /api/v1/mock-bank 아래에 모의 은행을 띄운다. */
    public record Mock(boolean enabled) {
    }
}
