package com.sneezecast.security.common.dto;

import com.sneezecast.security.common.enums.SecurityRole;
import java.time.Instant;
import java.util.Set;
import lombok.Builder;

@Builder
public record MemberLoginActive(
    // 인증된 회원 식별자
    long memberId,
    // 회원 권한
    SecurityRole role,
    // 토큰에 실린 권한 범위 (예: report:write). 없으면 빈 집합
    Set<String> scopes,
    // Access Token의 jti (블랙리스트 키)
    String tokenId,
    // Access Token의 만료 시각(exp). 폐기할 때 블랙리스트 TTL 을 남은 시간으로 맞추는 데 쓴다. 빌더에서 빠뜨리면 null
    Instant expiresAt,
    // 로그인 세션(기기) 식별자(sid claim). 세션 없이 발급된 토큰이면 null
    String sessionId
) {

    public MemberLoginActive {
        // 빌더에서 빠뜨려도 null 이 아니라 빈 집합이다. 외부에서 바꾸지 못하게 복사해 둔다.
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
