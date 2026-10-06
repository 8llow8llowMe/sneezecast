package com.sneezecast.domainlayer.member.adapter.out.auth;

import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthSessionProcessor;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberSessionRevokePort;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * member → auth 컨텍스트 교차 의존을 이 어댑터 한 지점으로 한정한다.
 *
 * <p>auth 의 세션 저장소 장애({@code AuthException(SESSION_STORE_UNAVAILABLE)})를 회원 예외로 바꾼다 — member 컨트롤러에는 auth advice 가 걸리지
 * 않아 그대로 두면 봉투 없는 500 이 된다.
 */
@Component
@RequiredArgsConstructor
public class MemberSessionRevokeAdapter implements MemberSessionRevokePort {

    private final AuthSessionProcessor authSessionProcessor;

    @Override
    public void revokeOtherSessions(long memberId, String keepSessionId) {
        try {
            if (keepSessionId == null) {
                authSessionProcessor.revokeAllSessions(memberId);
            } else {
                authSessionProcessor.revokeOtherSessions(memberId, keepSessionId);
            }
        } catch (AuthException exception) {
            throw new MemberException(MemberErrorCode.SESSION_REVOKE_UNAVAILABLE, exception);
        }
    }

    @Override
    public void revokeAllSessions(long memberId, String accessTokenId, Instant accessExpiresAt) {
        try {
            authSessionProcessor.revokeAllSessions(memberId, accessTokenId, accessExpiresAt);
        } catch (AuthException exception) {
            throw new MemberException(MemberErrorCode.SESSION_REVOKE_UNAVAILABLE, exception);
        }
    }
}
