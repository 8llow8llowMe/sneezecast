package com.sneezecast.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관심 동네 설정 ({@code region.interest.*}). 값은 application.yml 이 정본이고 환경변수로 받지 않는다 — 프론트 상수
 * ({@code INTEREST_REGION_LIMIT})와 같은 값이어야 해서 배포 환경마다 다르게 두지 않는다.
 *
 * <p>0 이하 값은 기동에서 실패시킨다 ({@link AuthSessionProperties} 와 같은 규칙).
 *
 * @param maxCount 회원당 관심 동네 상한 (내 동네는 세지 않는다). 칸 번호(slot) 1..maxCount 를 DB unique 로 묶어 동시 추가에서도 넘지 않게 한다.
 *                 목록 조회가 동네마다 surveillance 를 한 번씩 순차로 부르므로 늘릴 때는 응답 시간도 함께 본다
 */
@ConfigurationProperties(prefix = "region.interest")
public record RegionInterestProperties(
    int maxCount
) {

    public RegionInterestProperties {
        if (maxCount <= 0) {
            throw new IllegalStateException("region.interest.max-count 는 0 보다 커야 합니다: " + maxCount);
        }
    }
}
