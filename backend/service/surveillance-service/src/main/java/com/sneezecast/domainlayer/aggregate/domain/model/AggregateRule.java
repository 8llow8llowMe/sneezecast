package com.sneezecast.domainlayer.aggregate.domain.model;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 집계 단계 판정 규칙 (entity-design §2-2). 값은 설정 {@code aggregate.*} 에서 온다 ({@code AggregateProperties}). 스프링 없이 판정만 한다.
 *
 * <p>판정 순서 — 앞에서 걸리면 뒤는 보지 않는다.
 * <ol>
 *   <li>참여 &lt; {@code minSample} → INSUFFICIENT · LOW_SAMPLE</li>
 *   <li>{@link WeekState#CLOSED} 일 때만 — 전주 행이 있고 전주 참여 ≥ {@code minSample} 이며 |참여 − 전주 참여| ≥ 전주 참여 ×
 *       {@code unstableChangePercent}% → INSUFFICIENT · UNSTABLE. 전주는 마감 여부와 무관하게 본다. {@link WeekState#IN_PROGRESS} 면 건너뛴다.</li>
 *   <li>기준선 — 직전 {@code baselineLookbackWeeks} 주 중 <b>마감됐고 · 참여 ≥ {@code minSample} 이고 · UNSTABLE 이 아닌</b> 주를 최근 순으로
 *       {@code baselineWeeks} 개. 모자라면 INSUFFICIENT · NO_BASELINE. 기준선 비율 = Σ증상 / Σ참여 (주별 비율의 평균이 아니라 합산 비율).</li>
 *   <li>Δ = 증상 / 참여 − 기준선 비율. Δ ≥ {@code highDeltaPp}%p → HIGH, ≥ {@code slightDeltaPp}%p → SLIGHT, 그 밖(감소 포함) → NORMAL.</li>
 * </ol>
 * 비교는 모두 정수 곱셈으로 한다 — 부동소수로 나누면 정확히 3%p 같은 경계가 0.0299… 로 떨어진다. 곱셈이 long 을 넘으면 조용히 틀리지 않고
 * {@link ArithmeticException} 으로 실패한다.
 *
 * <p><b>값을 하나라도 바꾸면 {@code ruleVersion} 도 바꾼다.</b> 집계 행의 {@code rule_version} 으로 과거 판정을 설명할 수 있어야 한다.
 *
 * @param ruleVersion           규칙 버전. 공백이 아니고 {@value #RULE_VERSION_MAX_LENGTH}자 이하 ({@code rule_version} 컬럼)
 * @param minSample             최소 표본 (참여자 수). 시안의 {@code publicThresholdParticipants} 와 같은 값이다
 * @param unstableChangePercent 참여 급변으로 보는 전주 대비 변화율 (%)
 * @param slightDeltaPp         SLIGHT 하한 (%p)
 * @param highDeltaPp           HIGH 하한 (%p). {@code slightDeltaPp} 보다 크고 100 이하
 * @param baselineWeeks         기준선에 쓰는 주 수
 * @param baselineLookbackWeeks 기준선 주를 찾는 범위 (대상 주 직전 몇 주). {@code baselineWeeks} 이상
 */
public record AggregateRule(
    String ruleVersion,
    int minSample,
    int unstableChangePercent,
    int slightDeltaPp,
    int highDeltaPp,
    int baselineWeeks,
    int baselineLookbackWeeks
) {

    public static final int RULE_VERSION_MAX_LENGTH = 20;

    private static final long PERCENT = 100L;

    /**
     * 대상 주가 다 찼는지. 참여 급변(UNSTABLE)은 다 찬 주끼리만 비교한다 — 진행 중인 주는 월요일 0명부터 쌓이므로 다 찬 전주와 견주면 전주의
     * 절반에 닿을 때까지 늘 급변이 된다 (전주 300명 · 화요일 150명 → 급변).
     *
     * <p>재계산 · 마감 잡(#211)은 <b>현재 주 재계산을 {@link #IN_PROGRESS}, 마감(놓친 지난 주 포함)을 {@link #CLOSED}</b> 로 부른다. 마감
     * 판정만 UNSTABLE 을 남기므로 "마감됐고 UNSTABLE 이 아닌 주" 라는 기준선 자격의 뜻은 그대로다.
     */
    public enum WeekState {
        /** 아직 보고를 받는 현재 주. UNSTABLE 을 보지 않는다. */
        IN_PROGRESS,
        /** 보고가 끝난 지난 주 (마감 판정). 판정 순서를 모두 본다. */
        CLOSED
    }

    /** 설정이 잘못되면 기동에서 실패시키려고 메시지에 설정 키를 싣는다. */
    public AggregateRule {
        if (ruleVersion == null || ruleVersion.isBlank() || ruleVersion.length() > RULE_VERSION_MAX_LENGTH) {
            throw new IllegalArgumentException("aggregate.rule-version 은 공백이 아닌 " + RULE_VERSION_MAX_LENGTH + "자 이하여야 합니다: " + ruleVersion);
        }
        requirePositive("aggregate.min-sample", minSample);
        requirePositive("aggregate.unstable-change-percent", unstableChangePercent);
        requirePositive("aggregate.slight-delta-pp", slightDeltaPp);
        if (highDeltaPp <= slightDeltaPp || highDeltaPp > PERCENT) {
            throw new IllegalArgumentException(
                "aggregate.high-delta-pp 는 aggregate.slight-delta-pp(" + slightDeltaPp + ")보다 크고 100 이하여야 합니다: " + highDeltaPp);
        }
        requirePositive("aggregate.baseline-weeks", baselineWeeks);
        if (baselineLookbackWeeks < baselineWeeks) {
            throw new IllegalArgumentException(
                "aggregate.baseline-lookback-weeks 는 aggregate.baseline-weeks(" + baselineWeeks + ") 이상이어야 합니다: " + baselineLookbackWeeks);
        }
    }

    /**
     * 한 칸을 판정해 이 규칙 버전과 함께 묶는다.
     *
     * @see #judge
     */
    public AggregateCalculation calculate(String districtCode, ReportWeek isoWeek, WeekState weekState, AggregateCounts counts,
        Collection<DistrictWeeklyAggregate> history, LocalDateTime calculatedAt) {
        return new AggregateCalculation(districtCode, isoWeek, counts, judge(districtCode, isoWeek, weekState, counts, history), ruleVersion,
            calculatedAt);
    }

    /**
     * 대상 주 수치를 판정한다.
     *
     * @param districtCode 대상 행정동
     * @param isoWeek      대상 주
     * @param weekState    대상 주가 다 찼는지 — {@link WeekState#IN_PROGRESS} 면 UNSTABLE 을 보지 않는다
     * @param counts       대상 주 수치
     * @param history      같은 행정동의 지난 집계 행. 직전 {@code baselineLookbackWeeks} 주 밖의 행(대상 주 자신 · 이후 주 포함)은 무시하므로 넉넉히
     *                     넘겨도 된다
     * @throws IllegalArgumentException 다른 행정동 행이 섞였거나 범위 안에 같은 주 행이 둘인 경우 — 호출자 조회가 틀린 것이다
     */
    public AggregateJudgement judge(String districtCode, ReportWeek isoWeek, WeekState weekState, AggregateCounts counts,
        Collection<DistrictWeeklyAggregate> history) {
        Objects.requireNonNull(districtCode, "districtCode");
        Objects.requireNonNull(weekState, "weekState");
        Objects.requireNonNull(counts, "counts");
        Map<ReportWeek, DistrictWeeklyAggregate> window = window(districtCode, isoWeek, history);
        int participants = counts.participantCount();

        if (participants < minSample) {
            return AggregateJudgement.insufficient(InsufficientReason.LOW_SAMPLE);
        }
        DistrictWeeklyAggregate previous = window.get(isoWeek.previous());
        if (weekState == WeekState.CLOSED && previous != null && unstable(participants, previous.participantCount())) {
            return AggregateJudgement.insufficient(InsufficientReason.UNSTABLE);
        }

        long baselineParticipants = 0;
        long baselineSymptomatic = 0;
        int used = 0;
        for (int back = 1; back <= baselineLookbackWeeks && used < baselineWeeks; back++) {
            DistrictWeeklyAggregate row = window.get(isoWeek.minusWeeks(back));
            if (row != null && usableAsBaseline(row)) {
                baselineParticipants += row.participantCount();
                baselineSymptomatic += row.symptomaticCount();
                used++;
            }
        }
        if (used < baselineWeeks) {
            return AggregateJudgement.insufficient(InsufficientReason.NO_BASELINE);
        }

        AggregateLevel level = level(counts.symptomaticCount(), participants, baselineSymptomatic, baselineParticipants);
        return AggregateJudgement.judged(level, Math.toIntExact(baselineParticipants), Math.toIntExact(baselineSymptomatic));
    }

    // 직전 lookback 주의 행만 주별로 모은다.
    private Map<ReportWeek, DistrictWeeklyAggregate> window(String districtCode, ReportWeek isoWeek, Collection<DistrictWeeklyAggregate> history) {
        Set<ReportWeek> weeks = new HashSet<>();
        for (int back = 1; back <= baselineLookbackWeeks; back++) {
            weeks.add(isoWeek.minusWeeks(back));
        }
        Map<ReportWeek, DistrictWeeklyAggregate> byWeek = new HashMap<>();
        for (DistrictWeeklyAggregate row : history) {
            if (!districtCode.equals(row.districtCode())) {
                throw new IllegalArgumentException("다른 행정동의 집계 행이 섞였습니다. target=" + districtCode + ", row=" + row.districtCode());
            }
            if (weeks.contains(row.isoWeek()) && byWeek.putIfAbsent(row.isoWeek(), row) != null) {
                throw new IllegalArgumentException("같은 주의 집계 행이 둘입니다. district=" + districtCode + ", week=" + row.isoWeek());
            }
        }
        return byWeek;
    }

    // 전주가 표본 미달이면 비교할 기준이 없어 보지 않는다.
    private boolean unstable(long participants, long previousParticipants) {
        return previousParticipants >= minSample && Math.abs(participants - previousParticipants) * PERCENT >= unstableChangePercent * previousParticipants;
    }

    private boolean usableAsBaseline(DistrictWeeklyAggregate row) {
        return row.finalized() && row.participantCount() >= minSample && row.insufficientReason() != InsufficientReason.UNSTABLE;
    }

    // Δ ≥ pp%p  ⇔  sym/part − bSym/bPart ≥ pp/100  ⇔  100 × (sym × bPart − bSym × part) ≥ pp × part × bPart  (part, bPart > 0)
    private AggregateLevel level(long symptomatic, long participants, long baselineSymptomatic, long baselineParticipants) {
        long scaledDelta = Math.multiplyExact(PERCENT,
            Math.subtractExact(Math.multiplyExact(symptomatic, baselineParticipants), Math.multiplyExact(baselineSymptomatic, participants)));
        long scale = Math.multiplyExact(participants, baselineParticipants);
        if (scaledDelta >= Math.multiplyExact(highDeltaPp, scale)) {
            return AggregateLevel.HIGH;
        }
        if (scaledDelta >= Math.multiplyExact(slightDeltaPp, scale)) {
            return AggregateLevel.SLIGHT;
        }
        return AggregateLevel.NORMAL;
    }

    private static void requirePositive(String key, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 는 0 보다 커야 합니다: " + value);
        }
    }
}
