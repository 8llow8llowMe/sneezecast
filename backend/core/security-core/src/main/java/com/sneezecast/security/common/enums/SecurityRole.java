package com.sneezecast.security.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * access token 의 {@code role} claim. 회원당 단일 역할이다.
 *
 * <p>authority 는 {@code ROLE_} 접두어 없이 {@link #name()} 그대로 붙는다 — 검사는 {@code hasAuthority('OPERATOR')} 로 한다.
 * {@code OPERATOR} 는 운영자 검토·안내 발행 화면을 보호한다. 동의 여부는 역할이 아니라 scope 로 나눈다.
 */
@Getter
@RequiredArgsConstructor
public enum SecurityRole {
    USER("일반 회원"),
    OPERATOR("운영자");

    private final String displayName;

    public static SecurityRole from(String name) {
        return SecurityRole.valueOf(name.toUpperCase());
    }
}
