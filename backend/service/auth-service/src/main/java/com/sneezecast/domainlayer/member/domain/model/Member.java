package com.sneezecast.domainlayer.member.domain.model;

import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * 회원. <b>성명은 받지 않는다</b> (루트 CLAUDE.md 개인정보 — 성명 · 정확한 주소 · GPS 미수집).
 *
 * <p>프로필 이미지는 출처가 두 가지라 필드를 나눈다 — {@code profileImageKey}(직접 업로드한 오브젝트 키)와
 * {@code profileImageUrl}(소셜 제공자가 준 외부 URL). 둘은 배타적이고 표시 우선순위는 key 가 앞선다.
 *
 * @param password    비밀번호 해시. 카카오로만 로그인하는 회원은 null (비밀번호를 따로 정하지 않는다)
 * @param provider    로그인할 수 있는 소셜 제공자. null 이면 이메일 계정만 — 비밀번호가 있으면 이메일 로그인도 된다
 * @param withdrawnAt 탈퇴 시각. ACTIVE · SUSPENDED 회원은 null
 */
@Builder
public record Member(
    long id,
    String email,
    String password,
    String nickname,
    String profileImageUrl,
    String profileImageKey,
    SecurityRole role,
    OAuthProvider provider,
    MemberStatus status,
    LocalDateTime withdrawnAt
) {

}
