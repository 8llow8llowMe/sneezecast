package com.sneezecast;

import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import com.sneezecast.security.auth.jwt.JwtAuthProvider;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.Duration;
import java.util.Set;

/**
 * 테스트용 access token 발급. auth-service 와 같은 발급 코드(security-core {@link JwtAuthProvider})로 서명해, 발급 쪽과 검증 쪽의 알고리즘 · 키
 * 바이트 · claim 해석이 어긋나면 테스트가 깨지게 한다.
 */
public final class JwtTestTokens {

    private JwtTestTokens() {
    }

    public static JwtAuthProvider provider(String accessKey) {
        return new JwtAuthProvider(new JwtAuthProperties(accessKey, Duration.ofMinutes(5), accessKey, Duration.ofDays(1)));
    }

    /** {@code Authorization} 헤더 값 ({@code Bearer <token>}). 세션 ID 없이 발급한다. */
    public static String bearer(String accessKey, long memberId, SecurityRole role, String... scopes) {
        return "Bearer " + provider(accessKey).issueAccessToken(memberId, role, Set.of(scopes), null).value();
    }
}
