package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 로그인 설정 ({@code oauth.kakao.*}). 값은 application.yml 의 env 자리표시자가 정본이다.
 *
 * <p>앱 키 · 시크릿 · 콜백 주소는 기본값이 없고, <b>비었거나 공백이거나 env 가 풀리지 않은 자리표시자({@code ${...}})면 기동에서 실패</b>한다 —
 * 카카오 로그인만 조용히 KOE 오류로 깨진 채 서비스가 뜨는 것보다 낫다. 주소 · timeout 은 기본값이 있고 비거나 0 이하면 실패한다.
 *
 * @param clientId       REST API 키 ({@code KAKAO_CLIENT_ID}). 인가 주소에 실려 브라우저에 보이는 값이라 비밀값이 아니다
 * @param clientSecret   Client Secret ({@code KAKAO_CLIENT_SECRET}). 토큰 교환에만 쓴다. toString 에서 가린다
 * @param redirectUri    프론트 콜백 페이지 ({@code KAKAO_REDIRECT_URI}). 카카오 개발자 콘솔에 등록한 값과 같아야 한다
 * @param authorizeUri   인가 주소
 * @param tokenUri       토큰 교환 주소
 * @param userInfoUri    사용자 정보 주소
 * @param connectTimeout 연결 timeout
 * @param readTimeout    응답 timeout
 */
@ConfigurationProperties(prefix = "oauth.kakao")
public record KakaoOAuthProperties(
    String clientId,
    String clientSecret,
    String redirectUri,
    String authorizeUri,
    String tokenUri,
    String userInfoUri,
    Duration connectTimeout,
    Duration readTimeout
) {

    private static final String MASK = "****";

    public KakaoOAuthProperties {
        requireText("oauth.kakao.client-id", "KAKAO_CLIENT_ID", clientId);
        requireText("oauth.kakao.client-secret", "KAKAO_CLIENT_SECRET", clientSecret);
        requireText("oauth.kakao.redirect-uri", "KAKAO_REDIRECT_URI", redirectUri);
        requireText("oauth.kakao.authorize-uri", null, authorizeUri);
        requireText("oauth.kakao.token-uri", null, tokenUri);
        requireText("oauth.kakao.user-info-uri", null, userInfoUri);
        requirePositive("oauth.kakao.connect-timeout", "KAKAO_CONNECT_TIMEOUT", connectTimeout);
        requirePositive("oauth.kakao.read-timeout", "KAKAO_READ_TIMEOUT", readTimeout);
        clientId = clientId.strip();
        clientSecret = clientSecret.strip();
        redirectUri = redirectUri.strip();
    }

    /** 시크릿은 값도 길이도 드러내지 않는다 (coding-conventions §10). */
    @Override
    public String toString() {
        return "KakaoOAuthProperties[clientId=%s, clientSecret=%s, redirectUri=%s, authorizeUri=%s, tokenUri=%s, userInfoUri=%s, connectTimeout=%s, readTimeout=%s]"
            .formatted(clientId, MASK, redirectUri, authorizeUri, tokenUri, userInfoUri, connectTimeout, readTimeout);
    }

    /** 검증 실패 메시지에는 값 대신 설정 키 · 환경변수 이름만 넣는다. */
    private static void requireText(String key, String envName, String value) {
        // Binder 는 풀리지 않은 자리표시자를 문자열 그대로 넘긴다 — env 가 빠진 배포가 "${KAKAO_CLIENT_ID}" 로 뜨지 않게 막는다.
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalStateException(key + describe(envName) + " 가 비어 있습니다.");
        }
    }

    private static void requirePositive(String key, String envName, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + describe(envName) + " 는 0 보다 커야 합니다: " + value);
        }
    }

    private static String describe(String envName) {
        return envName == null ? "" : " (" + envName + ")";
    }
}
