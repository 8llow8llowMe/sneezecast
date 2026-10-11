package com.sneezecast.domainlayer.member.application.command;

import lombok.Builder;

/**
 * 알림 설정 변경 명령 — 보낸 항목만 바꾼다(PATCH). {@code null} 은 보내지 않은 항목이라 지금 값을 그대로 둔다. 웹 경계가 항목이 하나 이상이고 값이 JSON
 * boolean 인지 먼저 확인한다.
 */
@Builder
public record MemberNotificationSettingChangeCommand(
    Boolean weeklyReport,
    Boolean regionNotice
) {

}
