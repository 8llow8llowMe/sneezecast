package com.sneezecast.domainlayer.aggregate.application.info;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.GroupTrend;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.Builder;

/**
 * 행정동 하나의 한 주 공개 집계. 익명 집계라 보고자 · 회원 정보가 없다.
 *
 * <p><b>불변식: {@link AggregateLevel#INSUFFICIENT} 이면 {@code measured} 가 없다</b> — 집계 행에 숫자가 있어도 비율 · 증상 수 · 증상군 수치를 담지
 * 않는다 (루트 CLAUDE.md "자료 부족이면 수치 · 색으로 위험을 암시하지 않는다"). 참여자 수만은 늘 담는다 — 화면이 공개 기준까지의 진행을 보인다.
 *
 * @param code               SGIS 행정동 코드
 * @param name               행정동 이름
 * @param sigungu            시도 · 시군구 표기
 * @param active             현행이면 true, 폐지된 코드면 false
 * @param level              단계. 집계 행이 없으면 INSUFFICIENT
 * @param insufficientReason 자료 부족 이유. INSUFFICIENT 일 때만 (행이 없으면 LOW_SAMPLE)
 * @param participantCount   참여자 수. 행이 없으면 0
 * @param calculatedAt       마지막 재계산 시각 (저장 기준 지역 시각). 행이 없으면 null
 * @param measured           수치. INSUFFICIENT 면 null
 */
@Builder
public record DistrictWeeklyAggregateInfo(
    String code,
    String name,
    String sigungu,
    boolean active,
    AggregateLevel level,
    InsufficientReason insufficientReason,
    int participantCount,
    LocalDateTime calculatedAt,
    Measured measured
) {

    public DistrictWeeklyAggregateInfo {
        Objects.requireNonNull(level, "level");
        boolean insufficient = level == AggregateLevel.INSUFFICIENT;
        if (insufficient != (measured == null) || insufficient != (insufficientReason != null)) {
            throw new IllegalArgumentException("자료 부족이면 수치 없이 이유만, 아니면 수치만 있어야 합니다. code=" + code + ", level=" + level);
        }
    }

    /**
     * 수치가 있는 단계의 값. 비율은 퍼센트 소수 첫째 자리 반올림이다 ({@code AggregateRate}).
     *
     * @param symptomaticCount 증상군을 하나 이상 보고한 수
     * @param symptomRate      참여자 대비 증상 보고 비율 (%)
     * @param baselineRate     판정에 쓴 기준선 비율 (%) — 집계 행의 기준선 두 수로 계산한다
     * @param groups           증상군별 비율 · 전주 대비 변화. 선언 순서(호흡기 → 장관)
     */
    public record Measured(
        int symptomaticCount,
        BigDecimal symptomRate,
        BigDecimal baselineRate,
        List<GroupRate> groups
    ) {

        public Measured {
            Objects.requireNonNull(symptomRate, "symptomRate");
            Objects.requireNonNull(baselineRate, "baselineRate");
            groups = List.copyOf(groups);
        }
    }

    /**
     * @param group 증상군
     * @param rate  참여자 대비 그 증상군 보고 비율 (%)
     * @param trend 전주 대비 변화. 전주 행이 없거나 전주가 자료 부족이면 null
     */
    public record GroupRate(
        SymptomGroup group,
        BigDecimal rate,
        GroupTrend trend
    ) {

        public GroupRate {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(rate, "rate");
        }
    }
}
