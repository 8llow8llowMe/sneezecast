package com.sneezecast.domainlayer.member.adapter.out.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.service.processor.AuthSessionProcessor;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberSessionRevokeAdapterTest {

    private final AuthSessionProcessor authSessionProcessor = mock(AuthSessionProcessor.class);
    private final MemberSessionRevokeAdapter adapter = new MemberSessionRevokeAdapter(authSessionProcessor);

    @Test
    @DisplayName("남길 세션이 있으면 그 세션을 뺀 나머지만, 없으면(null) 전부 폐기한다")
    void delegatesByKeepSession() {
        adapter.revokeOtherSessions(42L, "keep-session");
        verify(authSessionProcessor).revokeOtherSessions(42L, "keep-session");
        verify(authSessionProcessor, never()).revokeAllSessions(anyLong());

        adapter.revokeOtherSessions(42L, null);
        verify(authSessionProcessor).revokeAllSessions(42L);
    }

    @Test
    @DisplayName("세션 저장소 장애(AUTH_017)는 MEMBER_009(503)로 바뀐다 — member 컨트롤러에서 봉투 없는 500 이 되지 않게")
    void storeFailureBecomesMemberException() {
        doThrow(new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE)).when(authSessionProcessor).revokeOtherSessions(anyLong(), anyString());

        assertThatThrownBy(() -> adapter.revokeOtherSessions(42L, "keep-session"))
            .isInstanceOfSatisfying(MemberException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.SESSION_REVOKE_UNAVAILABLE);
                assertThat(e.getCause()).isInstanceOf(AuthException.class);
            });
    }

    @Test
    @DisplayName("모든 기기 로그아웃은 요청 access 의 jti · 만료와 함께 넘기고, 저장소 장애는 MEMBER_009 로 바꾼다")
    void revokeAllSessionsWithRequestAccess() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        adapter.revokeAllSessions(42L, "access-jti", expiresAt);
        verify(authSessionProcessor).revokeAllSessions(42L, "access-jti", expiresAt);

        doThrow(new AuthException(AuthErrorCode.SESSION_STORE_UNAVAILABLE)).when(authSessionProcessor).revokeAllSessions(anyLong(), any(), any());
        assertThatThrownBy(() -> adapter.revokeAllSessions(42L, "access-jti", expiresAt))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.SESSION_REVOKE_UNAVAILABLE));
    }
}
