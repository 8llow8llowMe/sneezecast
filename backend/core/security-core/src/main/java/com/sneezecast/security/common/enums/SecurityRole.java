package com.sneezecast.security.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * access token 의 {@code role} claim. 회원당 단일 역할이다.
 *
 * <p>authority 는 {@code ROLE_} 접두어 없이 {@link #name()} 그대로 붙는다 — 검사는 {@code hasAuthority('OPERATOR')} 로 한다.
 * 동의 여부는 역할이 아니라 scope 로 나눈다.
 *
 * <ul>
 *   <li>{@code OPERATOR} — 운영 업무. 검토 후보 확인, 안내문 초안·승인·발행.</li>
 *   <li>{@code ADMIN} — 관리자 페이지. 회원·역할 부여, 자료 부족 판정 임계값 같은 운영 설정, 참조 데이터 수동 적재.
 *       운영 업무도 할 수 있으므로 운영 API 는 {@code hasAnyAuthority('OPERATOR', 'ADMIN')} 로 연다.</li>
 * </ul>
 *
 * <p>역할 계층(RoleHierarchy)은 두지 않는다. 역할이 셋뿐이라 API 마다 허용 역할을 명시하는 쪽이 읽기 쉽다.
 */
@Getter
@RequiredArgsConstructor
public enum SecurityRole {
    USER("일반 회원"),
    OPERATOR("운영자"),
    ADMIN("관리자");

    private final String displayName;

    public static SecurityRole from(String name) {
        return SecurityRole.valueOf(name.toUpperCase());
    }
}
