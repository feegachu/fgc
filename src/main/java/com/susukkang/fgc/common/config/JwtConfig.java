package com.susukkang.fgc.common.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * 설명 : 2차 JWT(HS256) 서명키와 인코더·디코더(#400, 인터페이스정의서 §2-1-1).
 *
 * 키는 fgc.jwt.secret(환경변수 FGC_JWT_SECRET)으로만 받는다. 저장소에 기본 키를 두지 않는 이유 —
 * 컨테이너(Containerfile.api)는 prod 프로필 없이 기본(local) 프로필로 뜨므로, 커밋된 기본 키가 있으면
 * 그 키로 누구나 SYSTEM_ADMIN 토큰을 서명할 수 있다.
 * 값이 비어 있으면 기동마다 새 임의 키를 만든다(재기동하면 발급된 토큰은 전부 무효 — 개발용으로는 충분).
 * prod 프로필은 값이 없으면 기동하지 않는다 — 임의 키로 뜨면 재기동·다중 인스턴스에서 토큰이 깨진다.
 * (application-prod.yml 은 .gitignore 대상이라 이 규칙을 설정 파일이 아니라 여기서 건다.)
 */
@Slf4j
@Configuration
public class JwtConfig {

    /** HS256 은 256비트 이상 키를 요구한다(RFC 7518 §3.2). */
    private static final int MIN_SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public JwtConfig(@Value("${fgc.jwt.secret:}") String secret, Environment environment) {
        this.key = new SecretKeySpec(secretBytes(secret, environment), "HmacSHA256");
    }

    private static byte[] secretBytes(String secret, Environment environment) {
        if (secret == null || secret.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("prod 프로필은 FGC_JWT_SECRET 환경변수가 필요합니다.");
            }
            log.warn("fgc.jwt.secret 이 비어 있어 임의 서명키를 생성했습니다. 재기동하면 발급된 JWT 가 모두 무효가 됩니다.");
            byte[] random = new byte[MIN_SECRET_BYTES];
            RANDOM.nextBytes(random);
            return random;
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            // 키 값 자체는 로그·예외 메시지에 남기지 않는다.
            throw new IllegalStateException("fgc.jwt.secret 은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다.");
        }
        return bytes;
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /** 기본 검증기(exp·nbf, 허용 오차 60초)에 서명 알고리즘을 HS256 하나로 고정한다. */
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
