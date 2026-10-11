package com.sneezecast.domainlayer.member.application.info;

import com.sneezecast.domainlayer.member.domain.model.MemberNotificationSetting;
import lombok.Builder;

/** 회원의 알림 설정 — 조회 · 변경 응답의 바탕. 저장한 행이 없으면 {@link #ALL_OFF} 다. */
@Builder
public record MemberNotificationSettingInfo(
    boolean weeklyReport,
    boolean regionNotice
) {

    /** 한 번도 바꾸지 않은 회원의 설정 — {@link MemberNotificationSetting#initial} 과 같은 값(모두 꺼짐)이다. */
    public static final MemberNotificationSettingInfo ALL_OFF = new MemberNotificationSettingInfo(false, false);

    public static MemberNotificationSettingInfo from(MemberNotificationSetting setting) {
        return new MemberNotificationSettingInfo(setting.weeklyReport(), setting.regionNotice());
    }
}
