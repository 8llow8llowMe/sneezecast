package com.sneezecast.domainlayer.member.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 소셜 로그인 제공자. 회원의 속성이므로 member 도메인에 둔다. {@code member.provider} 가 null 이면 이메일 계정이다.
 */
@Getter
@RequiredArgsConstructor
public enum OAuthProvider implements CodeNameDescribable {
    KAKAO("카카오");

    private final String displayName;
}
