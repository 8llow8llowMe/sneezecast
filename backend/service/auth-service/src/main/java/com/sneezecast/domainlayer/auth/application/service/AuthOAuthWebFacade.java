package com.sneezecast.domainlayer.auth.application.service;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthAuthorizeResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthLoginResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthSignupCommand;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthOAuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthOAuthCookieResult.Cookie;
import com.sneezecast.domainlayer.auth.application.info.AuthTokenInfo;
import com.sneezecast.domainlayer.auth.application.info.OAuthAuthorizationInfo;
import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthLoginDecision;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.auth.application.port.in.AuthOAuthWebUseCase;
import com.sneezecast.domainlayer.auth.application.port.out.query.OAuthMemberQueryResult;
import com.sneezecast.domainlayer.auth.application.service.presenter.AuthPresenter;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthTokenProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.OAuthLoginProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.OAuthMemberProcessor;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 소셜(카카오) 로그인 오케스트레이션. 이 Facade 에는 트랜잭션을 걸지 않는다 — 흐름 대부분이 제공자 HTTP 왕복과 Redis 왕복이고, DB 쓰기(가입 · 연결)는
 * {@link OAuthMemberProcessor} 로 좁혀 거기에 건다 (architecture-guide §3-1). 제공자 호출을 기다리는 동안 커넥션을 잡지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AuthOAuthWebFacade implements AuthOAuthWebUseCase {

    private final OAuthLoginProcessor oAuthLoginProcessor;
    private final OAuthMemberProcessor oAuthMemberProcessor;
    private final AuthTokenProcessor authTokenProcessor;
    private final AuthPresenter authPresenter;

    @Override
    public AuthOAuthCookieResult<AuthOAuthAuthorizeResponse> authorize(OAuthProvider provider, boolean switchAccount, String clientIp) {
        OAuthAuthorizationInfo info = oAuthLoginProcessor.authorize(provider, switchAccount, clientIp);
        return AuthOAuthCookieResult.of(authPresenter.toOAuthAuthorizeResponse(info), Cookie.STATE, info.state());
    }

    /**
     * state 확인 · 소비(쿠키 대조 → Redis) → 제공자 사용자 정보(HTTP, 이메일 검증) → 회원 판정 → 결과별 토큰 · 표.
     *
     * <p>state 를 제공자 호출보다 먼저 소비한다 — 이 브라우저가 시작하지 않은 콜백으로는 인가 코드를 교환하지 않는다.
     */
    @Override
    public AuthOAuthCookieResult<AuthOAuthLoginResponse> login(AuthOAuthLoginCommand command) {
        oAuthLoginProcessor.consumeState(command.provider(), command.state(), command.cookieState());
        OAuthMemberQueryResult oAuthMember = oAuthLoginProcessor.fetchMember(command.code());
        OAuthLoginDecision decision = oAuthLoginProcessor.resolve(command.provider(), oAuthMember);

        return switch (decision.outcome()) {
            case LOGGED_IN -> {
                AuthTokenInfo token = authTokenProcessor.issue(decision.member(), command.deviceLabel());
                yield AuthOAuthCookieResult.of(authPresenter.toOAuthLoggedInResponse(token), Cookie.REFRESH_TOKEN, token.refreshToken());
            }
            case SIGNUP_REQUIRED -> AuthOAuthCookieResult.of(authPresenter.toOAuthSignupRequiredResponse(decision), Cookie.SIGNUP_TICKET, decision.ticket());
            case LINK_REQUIRED -> AuthOAuthCookieResult.of(authPresenter.toOAuthLinkRequiredResponse(decision), Cookie.LINK_TICKET, decision.ticket());
        };
    }

    /**
     * 가입표 소비(Redis, 1회성) → 회원 · 동의 저장(DB 트랜잭션) → 토큰 발급(Redis, 커밋 뒤).
     *
     * <p>표를 저장보다 먼저 소비한다 — 같은 표로 동시에 들어온 요청 중 하나만 가입까지 간다. 저장이 실패하면 표는 이미 사라져 카카오 로그인부터 다시 한다.
     */
    @Override
    public AuthCookieResult<AuthTokenResponse> signup(AuthOAuthSignupCommand command) {
        OAuthSignupTicket ticket = oAuthLoginProcessor.consumeSignupTicket(command.signupTicket());
        Member member = oAuthMemberProcessor.signup(ticket, command);
        return toCookieResult(authTokenProcessor.issue(member, command.deviceLabel()));
    }

    /** 연결 확인표 소비(Redis, 1회성) → 상태 재확인 + 제공자 연결(DB 트랜잭션) → 연결 통보 메일(비동기) → 토큰 발급. */
    @Override
    public AuthCookieResult<AuthTokenResponse> link(String linkTicket, String deviceLabel) {
        OAuthLinkTicket ticket = oAuthLoginProcessor.consumeLinkTicket(linkTicket);
        Member member = oAuthMemberProcessor.link(ticket);
        oAuthLoginProcessor.notifyLinked(member);
        return toCookieResult(authTokenProcessor.issue(member, deviceLabel));
    }

    private AuthCookieResult<AuthTokenResponse> toCookieResult(AuthTokenInfo info) {
        return AuthCookieResult.of(authPresenter.toTokenResponse(info), info.refreshToken());
    }
}
