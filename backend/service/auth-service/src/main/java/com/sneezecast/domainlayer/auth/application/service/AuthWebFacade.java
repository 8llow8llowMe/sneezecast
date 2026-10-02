package com.sneezecast.domainlayer.auth.application.service;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthPasswordResetTokenResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthSessionsResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthTokenInfo;
import com.sneezecast.domainlayer.auth.application.port.in.AuthWebUseCase;
import com.sneezecast.domainlayer.auth.application.service.presenter.AuthPresenter;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthSessionProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthTokenProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.EmailVerificationProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.GeneralLoginProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.GeneralSignupProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.PasswordResetProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberCommandProcessor;
import com.sneezecast.domainlayer.member.application.service.support.EmailNormalizer;
import com.sneezecast.domainlayer.member.domain.model.Member;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 이메일 인증 · 가입 · 로그인 · 토큰 · 세션 · 비밀번호 재설정 오케스트레이션. 이 Facade 에는 트랜잭션을 걸지 않는다 — 흐름 대부분이 Redis 왕복과
 * 메일 발송(비동기)이고, DB 쓰기 구간은 {@link GeneralSignupProcessor#signup} · {@link MemberCommandProcessor#changePassword} 로 좁혀 거기에 건다
 * (architecture-guide §3-1). 로그인 ·
 * 재발급의 DB 접근은 회원 · 동의 조회뿐이라 트랜잭션 없이 각 조회가 따로 끝난다 — Redis 왕복 동안 커넥션을 잡지 않는다.
 */
@Slf4j
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
    private final PasswordResetProcessor passwordResetProcessor;
    private final MemberCommandProcessor memberCommandProcessor;

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

    @Override
    public void sendPasswordResetCode(String email, String clientIp) {
        passwordResetProcessor.sendCode(EmailNormalizer.normalize(email), clientIp);
    }

    @Override
    public AuthPasswordResetTokenResponse verifyPasswordResetCode(String email, String code, String clientIp) {
        return authPresenter.toPasswordResetTokenResponse(passwordResetProcessor.verifyCode(EmailNormalizer.normalize(email), code, clientIp));
    }

    /**
     * 토큰 소비(IP 상한 · 1회성 · 정상 회원) → 새 비밀번호 해시(트랜잭션 밖) → 모든 기기 세션 폐기(Redis) → 비밀번호 저장(DB 트랜잭션) → 로그인 잠금 해제.
     *
     * <ul>
     *   <li><b>세션을 먼저 끊는다.</b> 재설정은 비밀번호를 잃었거나 털렸을 때 하는 일이 많아, 비밀번호만 바뀌고 남의 기기가 로그인해 있는 상태를 만들지
     *       않는다. 폐기가 실패하면 {@code SESSION_STORE_UNAVAILABLE}(503)이고 비밀번호는 그대로다. 토큰은 이미 소비됐으므로 인증코드부터 다시 한다.</li>
     *   <li>폐기 뒤 저장이 실패하면 모든 기기만 로그아웃된 채 비밀번호는 그대로다 — 안전한 쪽의 실패라 되돌리지 않는다.</li>
     *   <li>저장이 커밋된 뒤 같은 범위(전부)로 한 번 더 끊는다 ({@link #revokeAllSessionsAfterCommit}).</li>
     *   <li>로그인 잠금은 저장이 끝난 뒤에 푼다 — 비밀번호가 바뀌지 않았는데 잠금만 풀리지 않게.</li>
     * </ul>
     */
    @Override
    public void resetPassword(String resetToken, String newPassword, String clientIp) {
        Member member = passwordResetProcessor.consumeToken(resetToken, clientIp);
        String encodedPassword = passwordEncoder.encode(newPassword);
        authSessionProcessor.revokeAllSessions(member.id());
        memberCommandProcessor.changePassword(member.id(), encodedPassword);
        revokeAllSessionsAfterCommit(member.id());
        passwordResetProcessor.releaseLoginLock(member.email());
    }

    /**
     * 비밀번호 저장이 커밋된 뒤 모든 세션을 한 번 더 끊는다. 1차 폐기와 커밋 사이에 옛 비밀번호로 BCrypt 를 통과한 로그인이 새 세션을 저장하면 그 세션이
     * 남는다 — 재설정은 탈취 상황에서 쓰는 일이 많아 이 틈을 닫는다. 이 Facade 는 트랜잭션을 열지 않으므로 저장 처리기가 돌아온 시점이 커밋 뒤다. 비밀번호는
     * 이미 바뀌었으니 실패해도 로그만 남기고 성공으로 끝낸다.
     */
    private void revokeAllSessionsAfterCommit(long memberId) {
        try {
            authSessionProcessor.revokeAllSessions(memberId);
        } catch (AuthException exception) {
            log.error("post-commit session revoke failed, tolerated memberId={} code={}", memberId, exception.getErrorCode().getCode());
        }
    }

    private AuthCookieResult<AuthTokenResponse> toCookieResult(AuthTokenInfo info) {
        return AuthCookieResult.of(authPresenter.toTokenResponse(info), info.refreshToken());
    }
}
