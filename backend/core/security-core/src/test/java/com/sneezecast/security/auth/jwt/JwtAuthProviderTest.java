package com.sneezecast.security.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.security.auth.jwt.JwtAuthProvider.IssuedToken;
import com.sneezecast.security.auth.jwt.JwtAuthProvider.RefreshTokenClaims;
import com.sneezecast.security.common.constant.JwtClaimNames;
import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.dto.MemberLoginActive;
import com.sneezecast.security.common.enums.SecurityRole;
import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.common.exception.SecurityJwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 토큰 파싱 실패가 <b>전부</b> {@link SecurityJwtException} 으로 나오는지, 그리고 역할·scope claim 이 발급 → 파싱을 거쳐 그대로 돌아오는지 고정한다.
 *
 * <p>필터는 이 예외만 잡아 401 을 쓴다. 다른 예외가 새면 Spring 기본 500 이 래퍼 없이 나가고,
 * FE 는 그것을 "일시 장애 · 재시도" 로 안내한다 — 재시도해도 절대 풀리지 않는 인증 오류인데.
 * 그래서 어떤 모양의 토큰이 와도 여기서 새는 예외가 없어야 한다.
 */
class JwtAuthProviderTest {

    private static final String ACCESS_KEY = "sneezecast-test-jwt-access-key-0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_KEY = "sneezecast-test-jwt-other--key-0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String REFRESH_KEY = "sneezecast-test-jwt-refresh-key-0123456789abcdef0123456789abcdef0123456789abcdef";

    private final JwtAuthProvider provider = new JwtAuthProvider(
        new JwtAuthProperties(ACCESS_KEY, Duration.ofMinutes(15), REFRESH_KEY, Duration.ofDays(14)));

    @ParameterizedTest
    @EnumSource(SecurityRole.class)
    @DisplayName("정상 토큰은 회원 식별자·권한·jti 를 돌려준다 (모든 역할)")
    void parsesIssuedToken(SecurityRole role) {
        String token = provider.issueAccessToken(42L, role, Set.of(), null).value();

        MemberLoginActive member = provider.parseAccessToken(token);

        assertThat(member.memberId()).isEqualTo(42L);
        assertThat(member.role()).isEqualTo(role);
        assertThat(member.tokenId()).isNotBlank();
    }

    @Test
    @DisplayName("발급한 scope 는 파싱 후에도 그대로 돌아온다")
    void scopesRoundTrip() {
        String token = provider.issueAccessToken(42L, SecurityRole.USER, Set.of(SecurityScope.REPORT_WRITE, "test:other"), null).value();

        MemberLoginActive member = provider.parseAccessToken(token);

        assertThat(member.scopes()).containsExactlyInAnyOrder(SecurityScope.REPORT_WRITE, "test:other");
    }

    @Test
    @DisplayName("scope 를 비워 발급하면 claim 을 싣지 않고, 파싱 결과는 빈 집합이다")
    void emptyScopesOmitClaim() {
        String token = provider.issueAccessToken(42L, SecurityRole.USER, Set.of(), null).value();

        assertThat(claimsOf(token).containsKey(SecurityScope.CLAIM_NAME)).isFalse();
        assertThat(provider.parseAccessToken(token).scopes()).isEmpty();
    }

    @Test
    @DisplayName("scope claim 이 아예 없는 토큰도 예외가 아니라 빈 집합이다 — 동의 전 회원의 정상 토큰")
    void tokenWithoutScopeClaimHasEmptyScopes() {
        String token = signedWith(ACCESS_KEY, "1", SecurityRole.USER.name(), new Date(System.currentTimeMillis() + 60_000));

        MemberLoginActive member = provider.parseAccessToken(token);

        assertThat(member.scopes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "report write", "report:write\t"})
    @DisplayName("비었거나 공백을 품은 scope 는 발급하지 않는다 — 공백 구분 claim 의 경계가 깨진다")
    void rejectsScopeWithWhitespace(String scope) {
        assertThatThrownBy(() -> provider.issueAccessToken(1L, SecurityRole.USER, Set.of(scope), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> malformedTokens() {
        String header = "eyJhbGciOiJIUzI1NiJ9";
        String payload = "eyJzdWIiOiIxIn0";
        return Stream.of(
            Arguments.of("2파트 — aa.bb", "aa.bb", SecurityErrorCode.TOKEN_INVALID),
            Arguments.of("디코딩 불가 문자 — h.p.!!!", header + "." + payload + ".!!!", SecurityErrorCode.TOKEN_INVALID),
            Arguments.of("서명 불일치 — h.p.AAAA", header + "." + payload + ".AAAA", SecurityErrorCode.TOKEN_SIGNATURE_INVALID),
            // base64url 로 디코딩할 수 없는 길이(1글자)의 서명. jjwt 가 서명 예외가 아닌 다른 예외를 던져 500 으로 새기 쉬운 갈래다.
            Arguments.of("디코딩 불가 길이 — h.p.x", header + "." + payload + ".x", SecurityErrorCode.TOKEN_INVALID),
            Arguments.of("빈 문자열", "", SecurityErrorCode.TOKEN_INVALID),
            Arguments.of("서명 없는 JWT (alg none 형태)", header + "." + payload + ".", SecurityErrorCode.TOKEN_INVALID)
        );
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedTokens")
    @DisplayName("형식이 깨진 토큰은 예외 종류와 무관하게 SecurityJwtException 으로 나온다")
    void mapsMalformedTokens(String description, String token, SecurityErrorCode expected) {
        assertThatThrownBy(() -> provider.parseAccessToken(token))
            .isInstanceOf(SecurityJwtException.class)
            .extracting(exception -> ((SecurityJwtException) exception).getErrorCode())
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("만료 + 서명 불일치는 서명 검증 실패다 — 서명을 못 믿으면 만료 클레임도 못 믿는다")
    void expiredWithWrongSignatureIsSignatureFailure() {
        String token = signedWith(OTHER_KEY, "1", SecurityRole.USER.name(), new Date(System.currentTimeMillis() - 60_000));

        assertThatThrownBy(() -> provider.parseAccessToken(token))
            .isInstanceOf(SecurityJwtException.class)
            .extracting(exception -> ((SecurityJwtException) exception).getErrorCode())
            .isEqualTo(SecurityErrorCode.TOKEN_SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("서명은 맞지만 만료된 토큰은 TOKEN_EXPIRED")
    void expiredTokenWithValidSignature() {
        String token = signedWith(ACCESS_KEY, "1", SecurityRole.USER.name(), new Date(System.currentTimeMillis() - 60_000));

        assertThatThrownBy(() -> provider.parseAccessToken(token))
            .isInstanceOf(SecurityJwtException.class)
            .extracting(exception -> ((SecurityJwtException) exception).getErrorCode())
            .isEqualTo(SecurityErrorCode.TOKEN_EXPIRED);
    }

    /**
     * 서명이 맞아도 클레임이 우리 발급 규약과 다르면 500 이 아니라 401 이다. 키가 새지 않으면 만들 수 없는
     * 토큰이지만, 만들 수 있게 됐을 때 서버 내부 오류로 보이게 두면 안 된다.
     */
    static Stream<Arguments> validSignatureBrokenClaims() {
        Date future = new Date(System.currentTimeMillis() + 60_000);
        return Stream.of(
            Arguments.of("subject 가 숫자가 아님", signedWith(ACCESS_KEY, "not-a-number", SecurityRole.USER.name(), future)),
            Arguments.of("role 클레임 없음", signedWith(ACCESS_KEY, "1", null, future)),
            Arguments.of("role 이 알 수 없는 값", signedWith(ACCESS_KEY, "1", "SUPERUSER", future)),
            Arguments.of("role 이 정의되지 않은 역할(MANAGER)", signedWith(ACCESS_KEY, "1", "MANAGER", future)),
            Arguments.of("subject 없음", signedWith(ACCESS_KEY, null, SecurityRole.USER.name(), future)),
            Arguments.of("scope 가 문자열이 아님(배열)", signedWith(ACCESS_KEY, "1", SecurityRole.USER.name(), future, List.of(SecurityScope.REPORT_WRITE)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validSignatureBrokenClaims")
    @DisplayName("서명은 맞지만 클레임이 규약과 다른 토큰은 TOKEN_INVALID")
    void mapsBrokenClaims(String description, String token) {
        assertThatThrownBy(() -> provider.parseAccessToken(token))
            .isInstanceOf(SecurityJwtException.class)
            .extracting(exception -> ((SecurityJwtException) exception).getErrorCode())
            .isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("refresh 토큰도 같은 규칙으로 실패한다 — access 키로 서명된 토큰을 refresh 로 내면 서명 실패")
    void refreshTokenParsedWithSameRules() {
        String accessToken = provider.issueAccessToken(1L, SecurityRole.USER, Set.of(), null).value();

        assertThatThrownBy(() -> provider.parseRefreshToken(accessToken))
            .isInstanceOf(SecurityJwtException.class)
            .extracting(exception -> ((SecurityJwtException) exception).getErrorCode())
            .isEqualTo(SecurityErrorCode.TOKEN_SIGNATURE_INVALID);
        assertThatThrownBy(() -> provider.parseRefreshToken("h.p.x"))
            .isInstanceOf(SecurityJwtException.class);
    }

    @Test
    @DisplayName("access 의 sid · exp · jti 는 발급 결과와 파싱 결과가 같다 — exp 는 초 단위로 잘린 같은 값이다")
    void accessTokenExposesSessionIdAndExpiry() {
        IssuedToken issued = provider.issueAccessToken(42L, SecurityRole.USER, Set.of(), "session-1");

        MemberLoginActive member = provider.parseAccessToken(issued.value());

        assertThat(member.sessionId()).isEqualTo("session-1");
        assertThat(member.tokenId()).isEqualTo(issued.tokenId());
        assertThat(member.expiresAt()).isEqualTo(issued.expiresAt());
        assertThat(issued.expiresAt()).isBetween(Instant.now().plus(Duration.ofMinutes(14)), Instant.now().plus(Duration.ofMinutes(15)));
        assertThat(claimsOf(issued.value()).get(JwtClaimNames.SESSION_ID)).isEqualTo("session-1");
    }

    @Test
    @DisplayName("sid 없이 발급한 access 는 claim 을 싣지 않고, 파싱 결과의 sessionId 는 null 이다")
    void accessTokenWithoutSessionId() {
        IssuedToken issued = provider.issueAccessToken(42L, SecurityRole.USER, Set.of(), null);

        assertThat(claimsOf(issued.value()).containsKey(JwtClaimNames.SESSION_ID)).isFalse();
        assertThat(provider.parseAccessToken(issued.value()).sessionId()).isNull();
    }

    static Stream<Arguments> brokenSessionIdClaims() {
        return Stream.of(
            Arguments.of("sid 가 숫자", 123),
            Arguments.of("sid 가 배열", List.of("session-1")),
            Arguments.of("sid 가 빈 문자열", " "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenSessionIdClaims")
    @DisplayName("access 의 sid 가 문자열이 아니거나 비었으면 TOKEN_INVALID")
    void rejectsBrokenAccessSessionId(String description, Object sid) {
        String token = signed(ACCESS_KEY, Map.of("role", SecurityRole.USER.name(), JwtClaimNames.SESSION_ID, sid));

        assertThatThrownBy(() -> provider.parseAccessToken(token))
            .isInstanceOf(SecurityJwtException.class)
            .extracting(exception -> ((SecurityJwtException) exception).getErrorCode())
            .isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("refresh 는 sid · jti · 회원을 돌려주고, 만료는 refresh 만료 설정이다")
    void refreshTokenRoundTrip() {
        IssuedToken issued = provider.issueRefreshToken(42L, "session-1");

        RefreshTokenClaims claims = provider.parseRefreshToken(issued.value());

        assertThat(claims.memberId()).isEqualTo(42L);
        assertThat(claims.sessionId()).isEqualTo("session-1");
        assertThat(claims.tokenId()).isEqualTo(issued.tokenId());
        assertThat(issued.expiresAt()).isBetween(Instant.now().plus(Duration.ofDays(14)).minusSeconds(5), Instant.now().plus(Duration.ofDays(14)));
    }

    @Test
    @DisplayName("같은 세션으로 같은 초 안에 다시 발급해도 jti 와 토큰이 다르다 — 회전 전후 토큰을 구별할 수 있어야 재사용을 잡는다")
    void refreshTokenGetsNewJtiEveryIssue() {
        IssuedToken first = provider.issueRefreshToken(42L, "session-1");
        IssuedToken second = provider.issueRefreshToken(42L, "session-1");

        assertThat(second.tokenId()).isNotEqualTo(first.tokenId());
        assertThat(second.value()).isNotEqualTo(first.value());
        assertThat(provider.parseRefreshToken(second.value()).sessionId()).isEqualTo("session-1");
    }

    @Test
    @DisplayName("발급 결과의 toString 은 토큰 원문을 가린다 — 로그 · 예외 메시지로 토큰이 새지 않는다")
    void issuedTokenMasksValue() {
        IssuedToken issued = provider.issueRefreshToken(42L, "session-1");

        assertThat(issued.toString()).doesNotContain(issued.value()).contains("****").contains(issued.tokenId());
    }

    @Test
    @DisplayName("빈 세션 아이디로는 refresh 를 발급하지 않는다")
    void refreshTokenRequiresSessionId() {
        assertThatThrownBy(() -> provider.issueRefreshToken(42L, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.issueRefreshToken(42L, null)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> brokenRefreshClaims() {
        return Stream.of(
            Arguments.of("sid 없음", Map.of(), "jti"),
            Arguments.of("sid 가 빈 문자열", Map.of(JwtClaimNames.SESSION_ID, ""), "jti"),
            Arguments.of("sid 가 숫자", Map.of(JwtClaimNames.SESSION_ID, 7), "jti"),
            Arguments.of("jti 없음", Map.of(JwtClaimNames.SESSION_ID, "session-1"), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenRefreshClaims")
    @DisplayName("refresh 의 sid 가 없거나 문자열이 아니거나, jti 가 없으면 TOKEN_INVALID — 회전할 세션 · 비교할 기준이 없다")
    void rejectsBrokenRefreshClaims(String description, Map<String, Object> claims, String jti) {
        var builder = Jwts.builder().subject("42").claims(claims).expiration(new Date(System.currentTimeMillis() + 60_000));
        if (jti != null) {
            builder.id(jti);
        }
        String token = builder.signWith(Keys.hmacShaKeyFor(REFRESH_KEY.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512).compact();

        assertThatThrownBy(() -> provider.parseRefreshToken(token))
            .isInstanceOf(SecurityJwtException.class)
            .extracting(exception -> ((SecurityJwtException) exception).getErrorCode())
            .isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("만료된 refresh 는 TOKEN_EXPIRED 다 — auth 가 재로그인 안내(AUTH_014)로 바꾼다")
    void expiredRefreshToken() {
        String token = Jwts.builder().id("jti").subject("42").claim(JwtClaimNames.SESSION_ID, "session-1")
            .expiration(new Date(System.currentTimeMillis() - 60_000))
            .signWith(Keys.hmacShaKeyFor(REFRESH_KEY.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512).compact();

        assertThatThrownBy(() -> provider.parseRefreshToken(token))
            .isInstanceOf(SecurityJwtException.class)
            .extracting(exception -> ((SecurityJwtException) exception).getErrorCode())
            .isEqualTo(SecurityErrorCode.TOKEN_EXPIRED);
    }

    private static String signed(String key, Map<String, Object> claims) {
        return Jwts.builder().id("jti").subject("1").claims(claims).expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512).compact();
    }

    private static Claims claimsOf(String token) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(ACCESS_KEY.getBytes(StandardCharsets.UTF_8))).build().parseSignedClaims(token).getPayload();
    }

    private static String signedWith(String key, String subject, String role, Date expiration) {
        return signedWith(key, subject, role, expiration, null);
    }

    private static String signedWith(String key, String subject, String role, Date expiration, Object scope) {
        var builder = Jwts.builder().id("jti").subject(subject).expiration(expiration);
        if (role != null) {
            builder.claim("role", role);
        }
        if (scope != null) {
            builder.claim(SecurityScope.CLAIM_NAME, scope);
        }
        return builder.signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512).compact();
    }
}
