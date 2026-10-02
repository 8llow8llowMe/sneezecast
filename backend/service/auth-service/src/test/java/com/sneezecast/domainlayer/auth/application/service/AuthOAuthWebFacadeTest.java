package com.sneezecast.domainlayer.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthLoginResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthOAuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthOAuthCookieResult.Cookie;
import com.sneezecast.domainlayer.auth.application.info.AuthTokenInfo;
import com.sneezecast.domainlayer.auth.application.info.OAuthAuthorizationInfo;
import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthLoginDecision;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.auth.application.port.out.query.OAuthMemberQueryResult;
import com.sneezecast.domainlayer.auth.application.service.presenter.AuthPresenter;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthTokenProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.OAuthLoginProcessor;
import com.sneezecast.domainlayer.auth.application.service.processor.OAuthMemberProcessor;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.security.common.enums.SecurityRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AuthOAuthWebFacadeTest {

    private static final OAuthProvider KAKAO = OAuthProvider.KAKAO;
    private static final String DEVICE = "iPhone · Safari";
    private static final Member MEMBER = Member.builder().id(42L).email("user@example.com").nickname("재채기탐정").role(SecurityRole.USER).provider(KAKAO)
        .status(MemberStatus.ACTIVE).build();
    private static final AuthTokenInfo TOKEN = AuthTokenInfo.builder().memberId(42L).role(SecurityRole.USER).accessToken("access").accessTokenExpiresIn(900)
        .refreshToken("refresh").pendingConsents(List.of()).reportWritable(false).build();
    private static final OAuthMemberQueryResult KAKAO_MEMBER = OAuthMemberQueryResult.builder().email("user@example.com").emailVerified(true)
        .emailValid(true).nickname("재채기탐정").build();
    private static final AuthOAuthLoginCommand LOGIN = AuthOAuthLoginCommand.builder().provider(KAKAO).code("code").state("state").cookieState("state")
        .deviceLabel(DEVICE).build();

    private OAuthLoginProcessor oAuthLoginProcessor;
    private OAuthMemberProcessor oAuthMemberProcessor;
    private AuthTokenProcessor authTokenProcessor;
    private AuthOAuthWebFacade facade;

    @BeforeEach
    void setUp() {
        oAuthLoginProcessor = mock(OAuthLoginProcessor.class);
        oAuthMemberProcessor = mock(OAuthMemberProcessor.class);
        authTokenProcessor = mock(AuthTokenProcessor.class);
        facade = new AuthOAuthWebFacade(oAuthLoginProcessor, oAuthMemberProcessor, authTokenProcessor, new AuthPresenter());
        when(authTokenProcessor.issue(any(), anyString())).thenReturn(TOKEN);
    }

    @Test
    @DisplayName("인가 — 주소는 응답으로, state 는 쿠키 값으로 나눠 돌려준다")
    void authorizeSplitsUrlAndState() {
        when(oAuthLoginProcessor.authorize(KAKAO, true, "203.0.113.10")).thenReturn(new OAuthAuthorizationInfo("https://kauth.test?state=s1", "s1"));

        AuthOAuthCookieResult<?> result = facade.authorize(KAKAO, true, "203.0.113.10");

        assertThat(result.cookie()).isEqualTo(Cookie.STATE);
        assertThat(result.cookieValue()).isEqualTo("s1");
    }

    @Test
    @DisplayName("로그인 — state 를 카카오 호출보다 먼저 소비하고, 기존 카카오 회원이면 토큰을 발급해 refresh 쿠키 값으로 돌려준다")
    void loginConsumesStateFirstThenIssuesToken() {
        when(oAuthLoginProcessor.fetchMember("code")).thenReturn(KAKAO_MEMBER);
        when(oAuthLoginProcessor.resolve(KAKAO, KAKAO_MEMBER)).thenReturn(OAuthLoginDecision.loggedIn(MEMBER));

        AuthOAuthCookieResult<AuthOAuthLoginResponse> result = facade.login(LOGIN);

        InOrder order = inOrder(oAuthLoginProcessor, authTokenProcessor);
        order.verify(oAuthLoginProcessor).consumeState(KAKAO, "state", "state");
        order.verify(oAuthLoginProcessor).fetchMember("code");
        order.verify(authTokenProcessor).issue(MEMBER, DEVICE);
        assertThat(result.cookie()).isEqualTo(Cookie.REFRESH_TOKEN);
        assertThat(result.cookieValue()).isEqualTo("refresh");
        assertThat(result.response().result()).isEqualTo("LOGGED_IN");
        assertThat(result.response().memberId()).isEqualTo("42");
        assertThat(result.response().accessTokenExpiresIn()).isEqualTo(900L);
        assertThat(result.response().nickname()).isNull();
    }

    @Test
    @DisplayName("로그인 — state 가 틀리면 카카오를 부르지 않는다")
    void invalidStateStopsBeforeKakao() {
        doThrow(new AuthException(AuthErrorCode.OAUTH_STATE_INVALID)).when(oAuthLoginProcessor).consumeState(any(), any(), any());

        assertThat(catchThrowableOfType(AuthException.class, () -> facade.login(LOGIN)).getErrorCode()).isEqualTo(AuthErrorCode.OAUTH_STATE_INVALID);
        verify(oAuthLoginProcessor, never()).fetchMember(anyString());
    }

    @Test
    @DisplayName("로그인 — 신규면 가입표, 이메일 계정이면 연결 확인표를 쿠키 값으로 주고 토큰은 발급하지 않는다")
    void loginReturnsTicketsWithoutTokens() {
        when(oAuthLoginProcessor.fetchMember("code")).thenReturn(KAKAO_MEMBER);
        when(oAuthLoginProcessor.resolve(KAKAO, KAKAO_MEMBER)).thenReturn(OAuthLoginDecision.signupRequired("signup-ticket", "재채기탐정"));

        AuthOAuthCookieResult<AuthOAuthLoginResponse> signup = facade.login(LOGIN);
        assertThat(signup.cookie()).isEqualTo(Cookie.SIGNUP_TICKET);
        assertThat(signup.cookieValue()).isEqualTo("signup-ticket");
        assertThat(signup.response()).isEqualTo(AuthOAuthLoginResponse.builder().result("SIGNUP_REQUIRED").nickname("재채기탐정").build());

        when(oAuthLoginProcessor.resolve(KAKAO, KAKAO_MEMBER)).thenReturn(OAuthLoginDecision.linkRequired("link-ticket", "u***@example.com"));
        AuthOAuthCookieResult<AuthOAuthLoginResponse> link = facade.login(LOGIN);
        assertThat(link.cookie()).isEqualTo(Cookie.LINK_TICKET);
        assertThat(link.cookieValue()).isEqualTo("link-ticket");
        assertThat(link.response()).isEqualTo(AuthOAuthLoginResponse.builder().result("LINK_REQUIRED").email("u***@example.com").build());

        verify(authTokenProcessor, never()).issue(any(), anyString());
    }

    @Test
    @DisplayName("가입 — 표 소비 → 가입(DB) → 바로 토큰 발급. 표가 만료면 가입 · 발급하지 않는다")
    void signupConsumesTicketThenIssuesToken() {
        AuthOAuthSignupCommand command = AuthOAuthSignupCommand.builder().signupTicket("t").termsAgreed(true).privacyAgreed(true).ageOver19Confirmed(true)
            .deviceLabel(DEVICE).build();
        OAuthSignupTicket ticket = new OAuthSignupTicket(KAKAO, "user@example.com", "재채기탐정");
        when(oAuthLoginProcessor.consumeSignupTicket("t")).thenReturn(ticket);
        when(oAuthMemberProcessor.signup(ticket, command)).thenReturn(MEMBER);

        AuthCookieResult<AuthTokenResponse> result = facade.signup(command);

        InOrder order = inOrder(oAuthLoginProcessor, oAuthMemberProcessor, authTokenProcessor);
        order.verify(oAuthLoginProcessor).consumeSignupTicket("t");
        order.verify(oAuthMemberProcessor).signup(ticket, command);
        order.verify(authTokenProcessor).issue(MEMBER, DEVICE);
        assertThat(result.refreshToken()).isEqualTo("refresh");
        assertThat(result.response().accessToken()).isEqualTo("access");

        when(oAuthLoginProcessor.consumeSignupTicket("expired")).thenThrow(new AuthException(AuthErrorCode.OAUTH_SIGNUP_TICKET_EXPIRED));
        AuthOAuthSignupCommand expired = AuthOAuthSignupCommand.builder().signupTicket("expired").termsAgreed(true).build();
        assertThat(catchThrowableOfType(AuthException.class, () -> facade.signup(expired)).getErrorCode())
            .isEqualTo(AuthErrorCode.OAUTH_SIGNUP_TICKET_EXPIRED);
        verify(oAuthMemberProcessor, never()).signup(any(), org.mockito.ArgumentMatchers.eq(expired));
    }

    @Test
    @DisplayName("연결 — 표 소비 → 연결(DB) → 통보 메일 → 토큰. 연결할 수 없으면(409) 메일 · 토큰 없이 끝난다")
    void linkNotifiesThenIssuesToken() {
        OAuthLinkTicket ticket = new OAuthLinkTicket(42L, KAKAO);
        when(oAuthLoginProcessor.consumeLinkTicket("t")).thenReturn(ticket);
        when(oAuthMemberProcessor.link(ticket)).thenReturn(MEMBER);

        AuthCookieResult<AuthTokenResponse> result = facade.link("t", DEVICE);

        InOrder order = inOrder(oAuthLoginProcessor, oAuthMemberProcessor, authTokenProcessor);
        order.verify(oAuthLoginProcessor).consumeLinkTicket("t");
        order.verify(oAuthMemberProcessor).link(ticket);
        order.verify(oAuthLoginProcessor).notifyLinked(MEMBER);
        order.verify(authTokenProcessor).issue(MEMBER, DEVICE);
        assertThat(result.refreshToken()).isEqualTo("refresh");

        when(oAuthLoginProcessor.consumeLinkTicket("t2")).thenReturn(ticket);
        when(oAuthMemberProcessor.link(ticket)).thenThrow(new AuthException(AuthErrorCode.OAUTH_LINK_NOT_ALLOWED));
        assertThat(catchThrowableOfType(AuthException.class, () -> facade.link("t2", DEVICE)).getErrorCode())
            .isEqualTo(AuthErrorCode.OAUTH_LINK_NOT_ALLOWED);
        verify(oAuthLoginProcessor, org.mockito.Mockito.times(1)).notifyLinked(any());
        verify(authTokenProcessor, org.mockito.Mockito.times(1)).issue(any(), anyString());
    }
}
