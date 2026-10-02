package com.sneezecast.domainlayer.auth.application.service;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthSessionsResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthTokenInfo;
import com.sneezecast.domainlayer.auth.application.port.in.AuthWebUseCase;
import com.sneezecast.domainlayer.auth.application.service.presenter.AuthPresenter;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthSessionProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthTokenProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.EmailVerificationProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.GeneralLoginProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.GeneralSignupProcessor;
import com.sneezecast.domainlayer.member.application.service.support.EmailNormalizer;
import com.sneezecast.domainlayer.member.domain.model.Member;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 이메일 인증 · 가입 · 로그인 · 토큰 · 세션 오케스트레이션. 이 Facade 에는 트랜잭션을 걸지 않는다 — 흐름 대부분이 Redis 왕복과 메일
 * 발송(비동기)이고, DB 쓰기 구간은 {@link GeneralSignupProcessor#signup} 하나로 좁혀 거기에 건다 (architecture-guide §3-1). 로그인 ·
 * 재발급의 DB 접근은 회원 · 동의 조회뿐이라 트랜잭션 없이 각 조회가 따로 끝난다 — Redis 왕복 동안 커넥션을 잡지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AuthWebFacade implements AuthWebUseCase {

    private final EmailVerificationProcessor emailVerificationProcessor;
    private final GeneralSignupProcessor generalSignupProcessor;
    private final PasswordEncoder passwordEncoder;
    private final GeneralLoginProcessor generalLoginProcessor;
    private final AuthTokenProcessor authTokenProcessor;
    private final AuthSessionProcessor authSessionProcessor;
    private final AuthPresenter authPresenter;

    @Override
    public void sendEmailVerificationCode(String email, String clientIp) {
        emailVerificationProcessor.sendCode(EmailNormalizer.normalize(email), clientIp);
    }

    @Override
    public void verifyEmailVerificationCode(String email, String code, String clientIp) {
        emailVerificationProcessor.verifyCode(EmailNormalizer.normalize(email), code, clientIp);
    }

    /**
     * 인증 완료 확인(Redis) → 비밀번호 해시 → 회원 · 동의 저장(DB 트랜잭션) → 인증 완료 표시 소비(Redis, 커밋 뒤).
     *
     * <ul>
     *   <li>BCrypt 해시(수십~수백 ms CPU)를 트랜잭션 밖에서 계산해 커넥션을 잡은 시간을 줄인다.</li>
     *   <li>표시를 커밋 뒤에 지워서, 저장이 실패하면 사용자가 인증을 다시 하지 않고 가입만 다시 시도할 수 있다.</li>
     * </ul>
     */
    @Override
    public void generalSignup(AuthGeneralSignupCommand command) {
        AuthGeneralSignupCommand normalized = command.normalized();
        emailVerificationProcessor.requireVerified(normalized.email());
        String encodedPassword = passwordEncoder.encode(normalized.password());
        generalSignupProcessor.signup(normalized, encodedPassword);
        emailVerificationProcessor.consumeVerified(normalized.email());
    }

    /** 자격 확인(시도 제한 · 상태) → 새 세션 · 토큰 발급. */
    @Override
    public AuthCookieResult<AuthTokenResponse> generalLogin(AuthGeneralLoginCommand command) {
        Member member = generalLoginProcessor.authenticate(EmailNormalizer.normalize(command.email()), command.password(), command.clientIp());
        return toCookieResult(authTokenProcessor.issue(member, command.deviceLabel()));
    }

    @Override
    public AuthCookieResult<AuthTokenResponse> reissueToken(String refreshToken) {
        return toCookieResult(authTokenProcessor.reissue(refreshToken));
    }

    @Override
    public void logout(long memberId, String sessionId, String accessTokenId, Instant accessExpiresAt) {
        authSessionProcessor.logout(memberId, sessionId, accessTokenId, accessExpiresAt);
    }

    @Override
    public AuthSessionsResponse getSessions(long memberId, String currentSessionId) {
        return authPresenter.toSessionsResponse(authSessionProcessor.getSessions(memberId, currentSessionId));
    }

    @Override
    public void revokeSession(long memberId, String sessionId) {
        authSessionProcessor.revokeSession(memberId, sessionId);
    }

    @Override
    public void revokeOtherSessions(long memberId, String currentSessionId) {
        authSessionProcessor.revokeOtherSessions(memberId, currentSessionId);
    }

    private AuthCookieResult<AuthTokenResponse> toCookieResult(AuthTokenInfo info) {
        return AuthCookieResult.of(authPresenter.toTokenResponse(info), info.refreshToken());
    }
}
