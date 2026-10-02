package com.sneezecast.domainlayer.member.application.port.in;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;

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
}
