package com.sneezecast.security.common.constant;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * access token 의 {@code scope} claim 규약과 scope 값 목록. 발급(auth)과 검증(각 서비스)이 이 한 곳을 본다.
 *
 * <p>claim 은 OAuth 관례대로 <b>공백 구분 문자열 하나</b>다 (예: {@code "report:write"}). 인증 주체에는 scope 마다
 * {@code SCOPE_<scope>} authority 가 붙으므로 서비스는 {@code @PreAuthorize("hasAuthority('SCOPE_report:write')")}
 * 로 검사한다. 접두어는 Spring Security 기본 변환기와 같다.
 */
public final class SecurityScope {

    /** 주간 건강 보고 쓰기. 민감정보(건강·증상) 처리 동의를 마친 회원에게만 발급한다. */
    public static final String REPORT_WRITE = "report:write";

    /** access token 의 scope claim 이름. */
    public static final String CLAIM_NAME = "scope";

    /** scope 에서 authority 를 만들 때 붙이는 접두어. */
    public static final String AUTHORITY_PREFIX = "SCOPE_";

    /** {@code @PreAuthorize} 에 문자열 상수로 쓸 수 있게 authority 이름을 컴파일 타임 상수로 둔다. */
    public static final String REPORT_WRITE_AUTHORITY = AUTHORITY_PREFIX + REPORT_WRITE;

    private static final String DELIMITER = " ";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SecurityScope() {
    }

    public static String authority(String scope) {
        return AUTHORITY_PREFIX + scope;
    }

    /**
     * scope 집합을 claim 문자열로 만든다. 비어 있으면 빈 문자열이다. 순서를 정렬로 고정해 같은 동의 상태가 늘 같은 claim 이 되게 한다.
     *
     * @throws IllegalArgumentException scope 가 비어 있거나 공백을 품은 경우 — 공백 구분 claim 에서 경계가 깨진다
     */
    public static String toClaim(Collection<String> scopes) {
        Objects.requireNonNull(scopes, "scopes");
        for (String scope : scopes) {
            if (scope == null || scope.isBlank() || WHITESPACE.matcher(scope).find()) {
                throw new IllegalArgumentException("scope must be a non-blank token without whitespace");
            }
        }
        return scopes.stream().distinct().sorted().collect(Collectors.joining(DELIMITER));
    }

    /** claim 문자열을 scope 집합으로 푼다. claim 이 없거나 비어 있으면 빈 집합이다 (예외가 아니다). */
    public static Set<String> fromClaim(String claim) {
        if (claim == null || claim.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(WHITESPACE.splitAsStream(claim.strip()).toList());
    }
}
