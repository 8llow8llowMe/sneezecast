package com.sneezecast.domainlayer.member.application.info;

import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.security.common.enums.SecurityRole;
import java.util.List;
import lombok.Builder;

/**
 * 내 정보 (S10). 비밀번호 해시는 싣지 않고 있는지만 싣는다.
 *
 * @param provider        소셜 제공자. null 이면 이메일 가입 계정
 * @param hasPassword     이메일 + 비밀번호로 로그인할 수 있는지 (카카오로만 로그인하는 회원은 false — 비밀번호를 따로 정하지 않는다)
 * @param pendingConsents 다시 동의해야 하는 필수 항목 — 로그인 · 재발급 응답과 같은 계산이다
 * @param reportWritable  주간 보고를 쓸 수 있는지 — access token 의 {@code report:write} 와 같은 계산이다
 */
@Builder
public record MemberMyInfo(
    long memberId,
    String email,
    String nickname,
    OAuthProvider provider,
    boolean hasPassword,
    SecurityRole role,
    List<ConsentType> pendingConsents,
    boolean reportWritable
) {

    public MemberMyInfo {
        pendingConsents = pendingConsents == null ? List.of() : List.copyOf(pendingConsents);
    }
}
