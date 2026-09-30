package com.sneezecast.security.resourceserver.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * 서비스 측 converter 가 {@code @PreAuthorize} 에 쓰이는 authority 를 만드는지 고정한다.
 * 커스텀 converter 라 Spring 기본 scope 변환이 돌지 않으므로, 여기서 빠지면 scope 검사가 전부 403 이 된다.
 */
class JwtToMemberConverterTest {

    private final JwtToMemberConverter converter = new JwtToMemberConverter();

    @Test
    @DisplayName("scope claim 이 있으면 역할 authority 와 SCOPE_report:write 를 함께 만든다")
    void convertsRoleAndScopeAuthorities() {
        Jwt jwt = jwt().claim("role", "USER").claim(SecurityScope.CLAIM_NAME, SecurityScope.REPORT_WRITE).build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("USER", SecurityScope.REPORT_WRITE_AUTHORITY);
        assertThat(SecurityScope.REPORT_WRITE_AUTHORITY).isEqualTo("SCOPE_report:write");
        MemberLoginActive principal = (MemberLoginActive) authentication.getPrincipal();
        assertThat(principal.memberId()).isEqualTo(42L);
        assertThat(principal.role()).isEqualTo(SecurityRole.USER);
        assertThat(principal.scopes()).containsExactly(SecurityScope.REPORT_WRITE);
        assertThat(principal.tokenId()).isEqualTo("jti-1");
        assertThat(authentication.isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("공백으로 구분된 scope 는 각각 SCOPE_ authority 가 된다")
    void splitsSpaceDelimitedScopes() {
        Jwt jwt = jwt().claim("role", "OPERATOR").claim(SecurityScope.CLAIM_NAME, "report:write test:other").build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("OPERATOR", "SCOPE_report:write", "SCOPE_test:other");
    }

    @Test
    @DisplayName("scope claim 이 없으면 역할 authority 만 만들고 scopes 는 빈 집합이다")
    void noScopeClaimYieldsOnlyRoleAuthority() {
        Jwt jwt = jwt().claim("role", "USER").build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("USER");
        assertThat(((MemberLoginActive) authentication.getPrincipal()).scopes()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenClaims")
    @DisplayName("서명은 맞고 claim 이 규약과 다르면 500 대신 인증 실패(401)로 거부한다")
    void rejectsBrokenClaimsAsAuthenticationFailure(String caseName, Jwt jwt) {
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }

    static Stream<Arguments> brokenClaims() {
        return Stream.of(
            Arguments.of("sub 가 숫자가 아님", jwt().subject("not-a-number").claim("role", "USER").build()),
            Arguments.of("sub 없음", Jwt.withTokenValue("token").header("alg", "HS512").jti("jti-1").claim("role", "USER").build()),
            Arguments.of("role 없음", jwt().claim(SecurityScope.CLAIM_NAME, SecurityScope.REPORT_WRITE).build()),
            Arguments.of("제거된 역할 ADMIN", jwt().claim("role", "ADMIN").build()),
            Arguments.of("scope 가 배열", jwt().claim("role", "USER").claim(SecurityScope.CLAIM_NAME, List.of("a", "b report:write")).build()));
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "HS512").subject("42").jti("jti-1");
    }
}
