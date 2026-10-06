package com.sneezecast.domainlayer.member.adapter.in.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.auth.adapter.in.web.support.RefreshCookieProvider;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/**
 * member 쪽 refresh 쿠키 삭제가 auth 의 정본({@link RefreshCookieProvider#clear()})과 같은 Set-Cookie 인지 고정한다. 이름 · Path · SameSite 중 하나만
 * 달라도 브라우저는 다른 쿠키로 보고 refresh 를 지우지 않는다 — 한쪽만 바뀌면 이 테스트가 깨진다.
 */
class MemberRefreshCookieTest {

    private static final String KEY = "sneezecast-member-cookie-test-key-0123456789abcdef0123456789abcdef0123456789";

    @Test
    @DisplayName("동의 철회의 쿠키 삭제 헤더는 auth 로그아웃의 쿠키 삭제 헤더와 같다")
    void clearMatchesAuthRefreshCookie() {
        RefreshCookieProvider authProvider = new RefreshCookieProvider(new JwtAuthProperties(KEY, Duration.ofMinutes(15), KEY, Duration.ofDays(14)));

        ResponseCookie memberClear = MemberRefreshCookie.clear();

        assertThat(memberClear.toString()).isEqualTo(authProvider.clear().toString());
        assertThat(memberClear.getName()).isEqualTo(RefreshCookieProvider.REFRESH_TOKEN_COOKIE);
        assertThat(memberClear.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(memberClear.getPath()).isEqualTo(authProvider.create("token").getPath());
    }
}
