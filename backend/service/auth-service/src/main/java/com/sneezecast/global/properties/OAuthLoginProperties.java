package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 소셜(카카오) 로그인 흐름 설정 ({@code auth.oauth.*}) — 일회용 값 수명과 인가 요청의 IP 상한. 값은 application.yml 의 env 자리표시자 기본값이 정본이다.
 * 쿠키 Max-Age 도 같은 수명을 쓴다 — 쿠키와 Redis 의 수명이 갈리면 쿠키가 먼저 죽어 아직 유효한 값을 가진 사용자가 거부된다.
 *
 * <p>0 이하 값은 기동에서 실패시킨다 ({@link EmailSendLimitProperties} 와 같은 규칙).
 *
 * @param stateTtl            인가 요청 state 의 수명 — 이 안에 카카오 동의 화면을 마치고 돌아와야 한다
 * @param signupTicketTtl     신규 가입표의 수명 — 동네 · 성인 확인 · 가입 동의 화면을 거치는 시간
 * @param linkTicketTtl       기존 이메일 계정 연결 확인표의 수명
 * @param authorizeIpMaxCount 한 IP 가 {@code authorizeIpWindow} 안에 받을 수 있는 인가 주소(state) 수. 인증 없이 부르는 API 라 state 키로 Redis 를
 *                            채우지 못하게 막는다
 * @param authorizeIpWindow   인가 요청 IP 상한의 창 (첫 요청부터, 고정 윈도우)
 */
@ConfigurationProperties(prefix = "auth.oauth")
public record OAuthLoginProperties(
    Duration stateTtl,
    Duration signupTicketTtl,
    Duration linkTicketTtl,
    int authorizeIpMaxCount,
    Duration authorizeIpWindow
) {

    public OAuthLoginProperties {
        requirePositive("auth.oauth.state-ttl", stateTtl);
        requirePositive("auth.oauth.signup-ticket-ttl", signupTicketTtl);
        requirePositive("auth.oauth.link-ticket-ttl", linkTicketTtl);
        if (authorizeIpMaxCount <= 0) {
            throw new IllegalStateException("auth.oauth.authorize-ip-max-count 는 0 보다 커야 합니다: " + authorizeIpMaxCount);
        }
        requirePositive("auth.oauth.authorize-ip-window", authorizeIpWindow);
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 는 0 보다 커야 합니다: " + value);
        }
    }
}
