package com.sneezecast.domainlayer.auth.application.port.in;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthSessionsResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import java.time.Instant;

public interface AuthWebUseCase {

    /** clientIp 는 IP 기준 발송 상한 검사에만 쓴다. */
    void sendEmailVerificationCode(String email, String clientIp);

    /** clientIp 는 IP 기준 검증 상한 검사에만 쓴다. */
    void verifyEmailVerificationCode(String email, String code, String clientIp);

    void generalSignup(AuthGeneralSignupCommand command);

    /** 응답 본문과 함께 refresh 쿠키 값을 돌려준다. */
    AuthCookieResult<AuthTokenResponse> generalLogin(AuthGeneralLoginCommand command);

    /** refresh 쿠키 값으로 토큰을 재발급하고 회전한 refresh 쿠키 값을 함께 돌려준다. 쿠키가 없으면 null 을 넘긴다. */
    AuthCookieResult<AuthTokenResponse> reissueToken(String refreshToken);

    /** 요청 access 의 세션을 지우고 그 access 를 폐기한다. sessionId 가 null 이면 폐기만 한다. */
    void logout(long memberId, String sessionId, String accessTokenId, Instant accessExpiresAt);

    AuthSessionsResponse getSessions(long memberId, String currentSessionId);

    void revokeSession(long memberId, String sessionId);

    void revokeOtherSessions(long memberId, String currentSessionId);
}
