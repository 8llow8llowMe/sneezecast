package com.sneezecast.domainlayer.member.application.port.out;

/**
 * 회원 보안 이벤트(비밀번호 변경 · 설정) 때 로그인 세션을 끊는 계약. member 컨텍스트가 auth 컨텍스트 구현(세션 저장소 · 블랙리스트)에 직접 의존하지
 * 않도록 경계를 둔다.
 */
public interface MemberSessionRevokePort {

    /**
     * {@code keepSessionId} 를 뺀 회원의 모든 세션을 지우고, 그 세션들이 받은 access token 도 바로 폐기한다(블랙리스트).
     *
     * @param keepSessionId 남길 세션(지금 기기). null 이면 전부 끊는다 — 세션을 알 수 없는 토큰이면 무엇을 남길지 정할 수 없다
     * @throws com.sneezecast.domainlayer.member.application.exception.MemberException 세션 저장소 장애면 {@code SESSION_REVOKE_UNAVAILABLE}(503)
     */
    void revokeOtherSessions(long memberId, String keepSessionId);
}
