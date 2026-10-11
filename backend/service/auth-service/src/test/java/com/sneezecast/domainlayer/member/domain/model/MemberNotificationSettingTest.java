package com.sneezecast.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberNotificationSettingTest {

    private static final MemberNotificationSetting ON_OFF = new MemberNotificationSetting(7L, 42L, true, false);

    @Test
    @DisplayName("처음 값은 모두 꺼짐이다 — 알림은 켠 회원에게만 보낸다")
    void initialIsAllOff() {
        assertThat(MemberNotificationSetting.initial(7L, 42L)).isEqualTo(new MemberNotificationSetting(7L, 42L, false, false));
    }

    @Test
    @DisplayName("보낸 항목만 바꾸고 null(보내지 않음)은 지금 값을 그대로 둔다 — ID · 회원도 그대로다")
    void changesOnlySentItems() {
        assertThat(ON_OFF.changedBy(null, true)).isEqualTo(new MemberNotificationSetting(7L, 42L, true, true));
        assertThat(ON_OFF.changedBy(false, null)).isEqualTo(new MemberNotificationSetting(7L, 42L, false, false));
        assertThat(ON_OFF.changedBy(false, true)).isEqualTo(new MemberNotificationSetting(7L, 42L, false, true));
    }

    @Test
    @DisplayName("아무것도 보내지 않으면 같은 값이고, 같은 값을 보내도 그대로다")
    void noChange() {
        assertThat(ON_OFF.changedBy(null, null)).isEqualTo(ON_OFF);
        assertThat(ON_OFF.changedBy(true, false)).isEqualTo(ON_OFF);
    }
}
