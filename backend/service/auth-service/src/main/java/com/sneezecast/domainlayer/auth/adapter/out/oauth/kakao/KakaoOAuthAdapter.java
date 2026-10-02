package com.sneezecast.domainlayer.auth.adapter.out.oauth.kakao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sneezecast.domainlayer.auth.adapter.out.oauth.kakao.dto.KakaoTokenClientResponse;
import com.sneezecast.domainlayer.auth.adapter.out.oauth.kakao.dto.KakaoUserClientResponse;
import com.sneezecast.domainlayer.auth.adapter.out.oauth.kakao.dto.KakaoUserClientResponse.KakaoAccount;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.port.out.OAuthAuthorizationUrlPort;
import com.sneezecast.domainlayer.auth.application.port.out.OAuthMemberQueryPort;
import com.sneezecast.domainlayer.auth.application.port.out.query.OAuthMemberQueryResult;
import com.sneezecast.global.properties.KakaoOAuthProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오 로그인 — 인가 주소 · 토큰 교환({@code POST kauth /oauth/token}) · 사용자 정보({@code GET kapi /v2/user/me}).
 *
 * <p><b>동의 항목은 {@value #SCOPE} 뿐이다</b> — 프로필 이미지는 받지 않는다(시안 "카카오에서는 이메일과 닉네임만 받아요"). 카카오 회원 ID 도 저장하지
 * 않는다(회원 식별은 이메일).
 *
 * <p><b>오류 변환.</b> 카카오가 거부한 4xx(인가 코드 만료 · 재사용 {@code invalid_grant}, 앱 설정 오류 {@code invalid_client} 등)는
 * {@code OAUTH_LOGIN_FAILED}, 5xx · 연결 실패 · timeout · 해석할 수 없는 응답은 {@code OAUTH_PROVIDER_UNAVAILABLE} 이다.
 *
 * <p><b>로그.</b> 인가 코드 · 카카오 토큰 · client secret · 이메일은 남기지 않는다. 카카오 오류 본문의 {@code error_description} 에는 인가 코드가 실릴 수
 * 있어 읽지 않고, 정해진 모양의 {@code error} · {@code error_code}(KOE…)만 남긴다. 예외 원인(cause)도 붙이지 않는다 — 응답 본문을 담고 있다.
 */
@Slf4j
@Component
public class KakaoOAuthAdapter implements OAuthMemberQueryPort, OAuthAuthorizationUrlPort {

    static final String SCOPE = "account_email,profile_nickname";
    static final String PROMPT_SELECT_ACCOUNT = "select_account";
    private static final MediaType FORM_UTF8 = new MediaType(MediaType.APPLICATION_FORM_URLENCODED, StandardCharsets.UTF_8);
    /** 로그에 남겨도 되는 카카오 오류 식별자 모양 (예: {@code invalid_grant}, {@code KOE320}). 그 밖의 값은 남기지 않는다. */
    private static final Pattern ERROR_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,40}");
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final KakaoOAuthProperties properties;

    public KakaoOAuthAdapter(@Qualifier("kakaoRestClient") RestClient restClient, KakaoOAuthProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String authorizationUrl(String state, boolean switchAccount) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.authorizeUri())
            .queryParam("response_type", "code")
            .queryParam("client_id", properties.clientId())
            .queryParam("redirect_uri", properties.redirectUri())
            .queryParam("scope", SCOPE)
            .queryParam("state", state);
        if (switchAccount) {
            builder.queryParam("prompt", PROMPT_SELECT_ACCOUNT);
        }
        return builder.encode().build().toUriString();
    }

    @Override
    public OAuthMemberQueryResult fetchMember(String authorizationCode) {
        String accessToken = exchangeToken(authorizationCode);
        KakaoUserClientResponse user = call("userInfo", () -> restClient.get()
            .uri(properties.userInfoUri())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .body(KakaoUserClientResponse.class));
        if (user == null) {
            log.error("kakao oauth response invalid operation=userInfo reason=emptyBody");
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        }

        KakaoAccount account = user.kakaoAccount();
        if (account == null) {
            return OAuthMemberQueryResult.builder().build();
        }
        return OAuthMemberQueryResult.builder()
            .email(account.email())
            .emailVerified(Boolean.TRUE.equals(account.emailVerified()))
            .emailValid(Boolean.TRUE.equals(account.emailValid()))
            .nickname(account.profile() == null ? null : account.profile().nickname())
            .build();
    }

    /** 인가 코드 → access token. 토큰 교환에만 client secret 이 실린다(폼 본문 — 주소 · 로그에 남지 않는다). */
    private String exchangeToken(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("code", authorizationCode);

        KakaoTokenClientResponse token = call("token", () -> restClient.post()
            .uri(properties.tokenUri())
            .contentType(FORM_UTF8)
            .body(form)
            .retrieve()
            .body(KakaoTokenClientResponse.class));
        if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
            log.error("kakao oauth response invalid operation=token reason=accessTokenMissing");
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        }
        return token.accessToken();
    }

    private <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (exception.getStatusCode().is4xxClientError()) {
                // invalid_grant(인가 코드 만료 · 재사용)가 대부분이지만 invalid_client(앱 키 · 시크릿 오류) · redirect_uri 불일치도 여기로 온다 —
                // error · errorCode 로 가려 설정 오류를 찾는다.
                ErrorIdentifiers error = errorIdentifiers(exception);
                log.warn("kakao oauth rejected operation={} status={} error={} errorCode={}", operation, status, error.error(), error.errorCode());
                throw new AuthException(AuthErrorCode.OAUTH_LOGIN_FAILED);
            }
            log.error("kakao oauth provider error operation={} status={}", operation, status);
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        } catch (RestClientException exception) {
            // 연결 실패 · timeout(ResourceAccessException) · 응답 해석 실패. 가장 안쪽 원인의 종류만 남긴다.
            log.error("kakao oauth call failed operation={} reason={}", operation,
                NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName());
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        }
    }

    /** 오류 본문에서 {@code error} · {@code error_code} 만 꺼낸다. 모양이 다르면 남기지 않는다(본문 다른 곳에 인가 코드가 있을 수 있다). */
    private static ErrorIdentifiers errorIdentifiers(RestClientResponseException exception) {
        try {
            JsonNode body = OBJECT_MAPPER.readTree(exception.getResponseBodyAsByteArray());
            return new ErrorIdentifiers(identifier(body, "error"), identifier(body, "error_code"));
        } catch (IOException | RuntimeException ignored) {
            return new ErrorIdentifiers("unknown", "unknown");
        }
    }

    private static String identifier(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isTextual() || !ERROR_IDENTIFIER.matcher(value.asText()).matches()) {
            return "unknown";
        }
        return value.asText();
    }

    private record ErrorIdentifiers(String error, String errorCode) {

    }
}
