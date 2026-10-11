package com.sneezecast.domainlayer.advisory.domain.model;

import com.sneezecast.domainlayer.advisory.domain.enums.ReviewSignal;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 예방 안내 검토 후보 신호 판정 (entity-design §2-2 "검토 후보", #266). 값은 설정 {@code advisory.review-candidate.*} 에서 온다
 * ({@code ReviewCandidateProperties}). 스프링 없이 판정만 한다.
 *
 * <ul>
 *   <li><b>참여 급증</b>({@link ReviewSignal#SURGE}) — 이번 주 · 전주가 모두 참여 부족(LOW_SAMPLE)이 아니고 (이번 − 전주) × 100 ≥ 전주 ×
 *       {@code surgeChangePercent}. 전주 행이 없으면 보지 않는다.</li>
 *   <li><b>반복 보고</b>({@link ReviewSignal#REPEAT}) — 참여 부족이 아니고 수정된 보고 수 × 100 ≥ 참여자 × {@code repeatRevisedPercent}.</li>
 *   <li><b>기준선 대비 변화</b>({@link ReviewSignal#BASELINE}) — 단계가 SLIGHT · HIGH. 판정은 집계({@code AggregateRule})가 이미 했다.</li>
 * </ul>
 * <b>참여 부족(LOW_SAMPLE) 칸은 신호가 나지 않는다.</b> 참여 급변(UNSTABLE) · 기준선 없음(NO_BASELINE)도 자료 부족이지만 급증 · 반복은 본다 — 큰 급증은
 * 마감 판정에서 정의상 UNSTABLE 이라 빼면 운영자가 가장 큰 급증을 못 본다(2026-10-11 결정). 비교는 정수 곱셈이다(경계 그대로 — 정확히 30% 는 급증).
 *
 * @param surgeChangePercent   참여 급증으로 보는 전주 대비 증가율 (%). 0 보다 크다
 * @param repeatRevisedPercent 반복 보고로 보는 참여자 대비 수정된 보고 비율 (%). 1 이상 100 이하
 */
public record ReviewCandidateRule(
    int surgeChangePercent,
    int repeatRevisedPercent
) {

    private static final long PERCENT = 100L;

    /** 설정이 잘못되면 기동에서 실패시키려고 메시지에 설정 키를 싣는다. */
    public ReviewCandidateRule {
        if (surgeChangePercent <= 0) {
            throw new IllegalArgumentException("advisory.review-candidate.surge-change-percent 는 0 보다 커야 합니다: " + surgeChangePercent);
        }
        if (repeatRevisedPercent <= 0 || repeatRevisedPercent > PERCENT) {
            throw new IllegalArgumentException("advisory.review-candidate.repeat-revised-percent 는 1 이상 100 이하여야 합니다: " + repeatRevisedPercent);
        }
    }

    /**
     * 동네 하나의 이번 주 신호. 결과는 {@link ReviewSignal} 선언 순서다.
     *
     * @param current  이번 주 집계 값
     * @param previous 같은 동네의 전주 집계 값. 행이 없으면 null
     */
    public Set<ReviewSignal> signals(WeekCounts current, WeekCounts previous) {
        Objects.requireNonNull(current, "current");
        Set<ReviewSignal> signals = EnumSet.noneOf(ReviewSignal.class);
        if (current.lowSample()) {
            return signals;
        }
        if (current.level() == AggregateLevel.SLIGHT || current.level() == AggregateLevel.HIGH) {
            signals.add(ReviewSignal.BASELINE);
        }
        if (previous != null && !previous.lowSample() && surge(current.participantCount(), previous.participantCount())) {
            signals.add(ReviewSignal.SURGE);
        }
        if (Math.multiplyExact(PERCENT, current.revisedReportCount()) >= Math.multiplyExact((long) repeatRevisedPercent, current.participantCount())) {
            signals.add(ReviewSignal.REPEAT);
        }
        return signals;
    }

    // (이번 − 전주) / 전주 ≥ p%  ⇔  100 × (이번 − 전주) ≥ p × 전주  (전주 > 0 — 참여 부족이 아니므로 최소 표본 이상이다)
    private boolean surge(long participants, long previousParticipants) {
        return Math.multiplyExact(PERCENT, participants - previousParticipants) >= Math.multiplyExact(surgeChangePercent, previousParticipants);
    }

    /**
     * 신호 판정에 쓰는 집계 한 칸의 값.
     *
     * @param participantCount   참여자 수
     * @param revisedReportCount 그 주에 한 번 이상 수정된 보고 수
     * @param level              집계 단계
     * @param insufficientReason 자료 부족 이유 (INSUFFICIENT 일 때만)
     */
    public record WeekCounts(
        int participantCount,
        int revisedReportCount,
        AggregateLevel level,
        InsufficientReason insufficientReason
    ) {

        public WeekCounts {
            Objects.requireNonNull(level, "level");
        }

        /** 참여 부족(LOW_SAMPLE)인지 — 신호를 내지 않는다. */
        public boolean lowSample() {
            return insufficientReason == InsufficientReason.LOW_SAMPLE;
        }
    }
}
