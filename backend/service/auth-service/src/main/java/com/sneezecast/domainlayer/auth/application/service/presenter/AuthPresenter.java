package com.sneezecast.domainlayer.auth.application.service.presenter;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.item.AuthSessionItem;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthPasswordResetTokenResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthSessionsResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.application.info.AuthSessionInfo;
import com.sneezecast.domainlayer.auth.application.info.AuthTokenInfo;
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
