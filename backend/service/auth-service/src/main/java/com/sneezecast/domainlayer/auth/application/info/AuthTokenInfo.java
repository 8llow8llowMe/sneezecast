package com.sneezecast.domainlayer.auth.application.info;

import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.security.common.enums.SecurityRole;
import java.util.List;
import lombok.Builder;

/**
 * 로그인 · 재발급 결과. refresh 토큰은 응답 본문이 아니라 쿠키로 내려간다 (컨트롤러가 나눈다).
 *
 * @param accessTokenExpiresIn access token 수명(초)
 * @param pendingConsents      다시 동의해야 하는 필수 항목 — 로그인은 막지 않고 화면이 재동의로 유도한다
 * @param reportWritable       access token 에 {@code report:write} 를 실었는지
 */
@Builder
public record AuthTokenInfo(
    long memberId,
    SecurityRole role,
    String accessToken,
    long accessTokenExpiresIn,
    String refreshToken,
    List<ConsentType> pendingConsents,
    boolean reportWritable
) {

    /** 토큰 원문은 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AuthTokenInfo[memberId=" + memberId + ", role=" + role + ", accessToken=****, accessTokenExpiresIn=" + accessTokenExpiresIn
            + ", refreshToken=****, pendingConsents=" + pendingConsents + ", reportWritable=" + reportWritable + "]";
    }
}
