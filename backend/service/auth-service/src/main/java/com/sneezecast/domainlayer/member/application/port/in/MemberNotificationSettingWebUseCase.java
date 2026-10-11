package com.sneezecast.domainlayer.member.application.port.in;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberNotificationSettingResponse;
import com.sneezecast.domainlayer.member.application.command.MemberNotificationSettingChangeCommand;

/** 알림 수신 설정. 두 요청 모두 설정 전부를 돌려준다. 건강정보가 아니라 {@code report:write} 를 요구하지 않는다. */
public interface MemberNotificationSettingWebUseCase {

    /** 한 번도 바꾸지 않았으면 모두 꺼짐이다(행을 만들지 않는다). */
    MemberNotificationSettingResponse getMyNotificationSettings(long memberId);

    /** 보낸 항목만 바꾸고 바뀐 뒤의 설정 전부를 돌려준다. */
    MemberNotificationSettingResponse changeMyNotificationSettings(long memberId, MemberNotificationSettingChangeCommand command);
}
