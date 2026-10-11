package com.sneezecast.domainlayer.member.domain.model;

import lombok.Builder;

/**
 * 회원이 고른 알림 수신 설정 — 회원당 최대 1행이다 (entity-design §1-7). 알림을 받을지 고른 값일 뿐 보낼 곳(푸시 구독)은 아니다(구독은 2단계).
 *
 * <p>행이 없는 회원은 {@link #initial} 과 같은 값(모두 꺼짐)으로 본다 — 알림은 켠 회원에게만 보낸다. 켠 · 끈 시각을 동의 이력으로 남기지 않는다
 * (광고성 정보가 아닌 서비스 알림의 수신 선택이다. 법적 의미의 알림 수신 동의는 2단계 푸시와 함께 {@code PUSH_NOTIFICATION} 으로 둔다).
 *
 * @param weeklyReport 주간 보고 요청 알림(월요일 아침). 발송 대상은 2단계에서 건강정보 동의({@code report:write})가 유효한 회원으로 다시 거른다
 * @param regionNotice 운영자가 검토 · 발행한 동네 안내 알림. 어느 동네(내 동네 · 관심 동네)의 안내를 보낼지는 발송 대상 규칙이라 이 값과 무관하다
 */
@Builder
public record MemberNotificationSetting(
    long id,
    long memberId,
    boolean weeklyReport,
    boolean regionNotice
) {

    /** 첫 변경 때 만드는 행의 바탕 — 모두 꺼짐. 행이 없는 회원의 조회 값도 이와 같다. */
    public static MemberNotificationSetting initial(long id, long memberId) {
        return new MemberNotificationSetting(id, memberId, false, false);
    }

    /**
     * 보낸 항목만 바꾼 설정을 돌려준다 — {@code null} 은 보내지 않은 항목이라 지금 값을 그대로 둔다. 다른 탭 · 기기에서 바꾼 다른 항목을 덮지 않으려는
     * 것이다(PATCH).
     */
    public MemberNotificationSetting changedBy(Boolean weeklyReport, Boolean regionNotice) {
        return new MemberNotificationSetting(
            id,
            memberId,
            weeklyReport == null ? this.weeklyReport : weeklyReport,
            regionNotice == null ? this.regionNotice : regionNotice);
    }
}
