package com.sneezecast.domainlayer.auth.application.port.in;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthAuthorizeResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthLoginResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthSignupCommand;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthOAuthCookieResult;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;

/** 소셜(카카오) 로그인 — 인가 주소 · 콜백 로그인 · 동의 후 가입 · 기존 이메일 계정 연결. */
public interface AuthOAuthWebUseCase {

    /** 인가 주소와 함께 state 쿠키 값을 돌려준다. clientIp 는 IP 기준 발급 상한 검사에만 쓴다. */
    AuthOAuthCookieResult<AuthOAuthAuthorizeResponse> authorize(OAuthProvider provider, boolean switchAccount, String clientIp);

    /** 결과에 따라 refresh 토큰 · 가입표 · 연결 확인표 중 하나를 쿠키 값으로 돌려준다. */
    AuthOAuthCookieResult<AuthOAuthLoginResponse> login(AuthOAuthLoginCommand command);

    /** 가입표로 가입하고 바로 로그인한다(비밀번호가 없어 따로 로그인할 수 없다). refresh 쿠키 값을 함께 돌려준다. */
    AuthCookieResult<AuthTokenResponse> signup(AuthOAuthSignupCommand command);

    /**
     * 연결 확인표로 이메일 계정에 소셜 로그인을 연결하고 로그인한다. refresh 쿠키 값을 함께 돌려준다.
     *
     * @param linkTicket  연결 확인표 쿠키. 없으면 null
     * @param deviceLabel User-Agent 를 "OS · 브라우저" 로 줄인 기기 이름
     */
    AuthCookieResult<AuthTokenResponse> link(String linkTicket, String deviceLabel);
}
