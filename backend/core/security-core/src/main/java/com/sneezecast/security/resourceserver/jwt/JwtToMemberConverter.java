package com.sneezecast.security.resourceserver.jwt;

import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.jwt.JwtAuthentication;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * 검증을 마친 JWT 를 회원 인증 주체로 바꾼다. 커스텀 converter 를 쓰므로 Spring 기본 scope 변환은 돌지 않는다 —
 * {@code SCOPE_<scope>} authority 는 {@link JwtAuthentication#authenticated} 가 직접 붙인다.
 *
 * <p>서명은 맞는데 claim 이 규약과 다른 토큰은 {@link InvalidBearerTokenException} 으로 거부한다. 여기서 런타임 예외가 그대로
 * 새면 {@code BearerTokenAuthenticationFilter} 가 잡지 못해 응답 봉투 없는 500 이 된다. auth 쪽 {@code JwtAuthProvider} 와 같은 판정
 * (claim 불일치 → 401 {@code TOKEN_INVALID})을 맞추기 위한 것이다.
 */
public class JwtToMemberConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String CLAIM_ROLE = "role";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        MemberLoginActive principal;
        try {
            principal = MemberLoginActive.builder()
                .memberId(Long.parseLong(jwt.getSubject()))
                .role(SecurityRole.from(jwt.getClaimAsString(CLAIM_ROLE)))
                .scopes(SecurityScope.fromClaim(requireStringOrNull(jwt.getClaim(SecurityScope.CLAIM_NAME))))
                .tokenId(jwt.getId())
                .build();
        } catch (NullPointerException | IllegalArgumentException exception) {
            // IllegalArgumentException 이 sub 파싱 실패(NumberFormatException)와 없는 역할 이름을 함께 받는다
            throw new InvalidBearerTokenException("invalid token claims", exception);
        }

        return JwtAuthentication.authenticated(principal);
    }

    // getClaimAsString 은 배열도 toString() 으로 바꿔 통과시킨다. scope 는 공백 구분 문자열만 받는다.
    private static String requireStringOrNull(Object claim) {
        if (claim == null || claim instanceof String) {
            return (String) claim;
        }
        throw new IllegalArgumentException("scope claim 은 문자열이어야 합니다");
    }
}
