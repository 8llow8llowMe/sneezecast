package com.sneezecast.domainlayer.auth.application.service.presenter;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.item.AuthSessionItem;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthAuthorizeResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthLoginResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthPasswordResetTokenResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthSessionsResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.info.AuthSessionInfo;
import com.sneezecast.domainlayer.auth.application.info.AuthTokenInfo;
import com.sneezecast.domainlayer.auth.application.info.OAuthAuthorizationInfo;
import com.sneezecast.domainlayer.auth.application.model.OAuthLoginDecision;
import com.sneezecast.domainlayer.auth.application.model.OAuthLoginOutcome;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 인증 Info → 응답 DTO. ID 는 문자열로(coding-conventions §2-1), enum 은 {@code name()} 으로 바꾼다.
 */
@Component
public class AuthPresenter {

    public AuthTokenResponse toTokenResponse(AuthTokenInfo info) {
        return AuthTokenResponse.builder()
            .memberId(String.valueOf(info.memberId()))
            .role(info.role().name())
            .accessToken(info.accessToken())
            .accessTokenExpiresIn(info.accessTokenExpiresIn())
            .pendingConsents(info.pendingConsents().stream().map(Enum::name).toList())
            .reportWritable(info.reportWritable())
            .build();
    }

    public AuthPasswordResetTokenResponse toPasswordResetTokenResponse(String resetToken) {
        return new AuthPasswordResetTokenResponse(resetToken);
    }

    public AuthOAuthAuthorizeResponse toOAuthAuthorizeResponse(OAuthAuthorizationInfo info) {
        return new AuthOAuthAuthorizeResponse(info.authorizeUrl());
    }

    /** 카카오 로그인 → 로그인 응답과 같은 필드에 {@code result} 를 더한다. */
    public AuthOAuthLoginResponse toOAuthLoggedInResponse(AuthTokenInfo info) {
        return AuthOAuthLoginResponse.builder()
            .result(OAuthLoginOutcome.LOGGED_IN.name())
            .memberId(String.valueOf(info.memberId()))
            .role(info.role().name())
            .accessToken(info.accessToken())
            .accessTokenExpiresIn(info.accessTokenExpiresIn())
            .pendingConsents(info.pendingConsents().stream().map(Enum::name).toList())
            .reportWritable(info.reportWritable())
            .build();
    }

    public AuthOAuthLoginResponse toOAuthSignupRequiredResponse(OAuthLoginDecision decision) {
        return AuthOAuthLoginResponse.builder().result(OAuthLoginOutcome.SIGNUP_REQUIRED.name()).nickname(decision.nickname()).build();
    }

    public AuthOAuthLoginResponse toOAuthLinkRequiredResponse(OAuthLoginDecision decision) {
        return AuthOAuthLoginResponse.builder().result(OAuthLoginOutcome.LINK_REQUIRED.name()).email(decision.maskedEmail()).build();
    }

    /** 시각은 초 단위로 자른다 — 화면은 "몇 분 전" 정도만 쓰고, 저장소의 ms 정밀도를 내보낼 이유가 없다. */
    public AuthSessionsResponse toSessionsResponse(List<AuthSessionInfo> sessions) {
        List<AuthSessionItem> items = sessions.stream()
            .map(session -> AuthSessionItem.builder()
                .sessionId(session.sessionId())
                .deviceLabel(session.deviceLabel())
                .createdAt(session.createdAt().truncatedTo(ChronoUnit.SECONDS))
                .lastUsedAt(session.lastUsedAt().truncatedTo(ChronoUnit.SECONDS))
                .current(session.current())
                .build())
            .toList();
        return AuthSessionsResponse.builder()
            .sessions(items)
            .totalCount(items.size())
            .build();
    }
}
