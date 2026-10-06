package com.sneezecast.domainlayer.member.application.port.in;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberConsentStatusResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import java.time.Instant;

public interface MemberWebUseCase {

    MemberMyInfoResponse getMyInfo(long memberId);

    /** @param nickname 검증을 통과한 닉네임(앞뒤 공백은 여기서 지운다) */
    MemberMyInfoResponse updateMyInfo(long memberId, String nickname);

    /**
     * 현재 비밀번호를 확인하고 바꾼다. 성공하면 지금 기기({@code sessionId})를 뺀 모든 기기를 로그아웃시킨다.
     *
     * @param sessionId 요청 access 의 세션(sid). null 이면 모든 기기를 로그아웃시킨다
     */
    void changePassword(long memberId, String sessionId, String currentPassword, String newPassword);

    /** 약관 재동의 · 건강정보 동의. 이미 현재 버전으로 유효하면 아무것도 하지 않고 성공한다. 응답은 동의 뒤의 상태다. */
    MemberConsentStatusResponse agreeConsent(long memberId, ConsentType type, String documentVersion);

    /**
     * 건강정보 동의를 철회하고 보고 파기를 요청한 뒤(한 트랜잭션) 모든 기기를 로그아웃시킨다(요청한 access 포함). 철회할 동의가 없으면 아무것도 하지 않는다.
     *
     * @param accessTokenId   요청 access 의 jti
     * @param accessExpiresAt 요청 access 의 만료 시각
     */
    MemberConsentWithdrawResult withdrawConsent(long memberId, ConsentType type, String accessTokenId, Instant accessExpiresAt);
}
