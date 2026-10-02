package com.sneezecast.security.common.constant;

/**
 * 역할 · 세션 claim 이름. 발급({@code JwtAuthProvider})과 검증(auth 필터 · 서비스 {@code JwtToMemberConverter})이 이 한 곳을 본다.
 * scope claim 은 값 규약과 함께 {@link SecurityScope#CLAIM_NAME} 에 있다.
 */
public final class JwtClaimNames {

    /** 단일 역할 ({@code USER} / {@code OPERATOR} / {@code ADMIN}). */
    public static final String ROLE = "role";

    /**
     * 로그인 세션(기기) 식별자. 로그인 때 한 번 정해지고 토큰을 회전해도 바뀌지 않는다. access · refresh 토큰에 함께 싣는다 — access 의
     * sid 로 로그아웃 · 기기 목록의 "현재 기기" 를 가리고, refresh 의 sid 로 회전할 세션을 찾는다.
     */
    public static final String SESSION_ID = "sid";

    private JwtClaimNames() {
    }
}
