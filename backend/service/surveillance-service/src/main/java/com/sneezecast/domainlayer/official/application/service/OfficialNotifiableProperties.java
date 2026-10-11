package com.sneezecast.domainlayer.official.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 전수신고 표시 규칙 ({@code official.notifiable.*}, entity-design §3-2). 값은 application.yml 이 정본이다.
 *
 * <p>전수신고 주별 값은 신고 지연으로 최근 주가 계속 늘어난다 (data-api-analysis §2-3) — 진행 중인 주나 바로 지난 주를 보이면 감소 추세처럼
 * 오해된다. 그래서 공식 정보 조회는 <b>지금 주(질병관리청 주, 일요일 시작)에서 {@code displayLagWeeks} 주 전</b>보다 최근 주를 보이지 않는다.
 *
 * <p>잘못된 값 · 빠진 값이면 기동에서 실패한다 — 틀린 값이 조용히 기본값으로 바뀌면 운영자가 설정이 먹었는지 알 수 없다.
 *
 * @param displayLagWeeks 0 이상 {@value #MAX_DISPLAY_LAG_WEEKS} 이하. 0 이면 지금 주까지 보인다. 상한은 적재 범위(올해 + 전년)를 넘겨 보일 주가
 *                        사라지지 않게 둔 값이다
 */
@ConfigurationProperties(prefix = "official.notifiable")
public record OfficialNotifiableProperties(Integer displayLagWeeks) {

    static final int MAX_DISPLAY_LAG_WEEKS = 52;

    public OfficialNotifiableProperties {
        if (displayLagWeeks == null || displayLagWeeks < 0 || displayLagWeeks > MAX_DISPLAY_LAG_WEEKS) {
            throw new IllegalStateException(
                "official.notifiable.display-lag-weeks 는 0 이상 " + MAX_DISPLAY_LAG_WEEKS + " 이하여야 합니다: " + displayLagWeeks);
        }
    }
}
