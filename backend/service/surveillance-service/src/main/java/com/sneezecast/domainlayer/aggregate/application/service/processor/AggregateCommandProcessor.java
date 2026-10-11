package com.sneezecast.domainlayer.aggregate.application.service.processor;

import com.sneezecast.domainlayer.aggregate.application.model.AggregateWeekResult;
import com.sneezecast.domainlayer.aggregate.application.port.out.AggregateReportQueryPort;
import com.sneezecast.domainlayer.aggregate.application.port.out.DistrictWeeklyAggregateRepositoryPort;
import com.sneezecast.domainlayer.aggregate.application.service.AggregateProperties;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCalculation;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCounts;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRule;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRule.WeekState;
import com.sneezecast.domainlayer.aggregate.domain.model.DistrictWeeklyAggregate;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행정동 × 주 집계 재계산 · 마감 (entity-design §2-2). <b>주 하나가 트랜잭션 하나다</b> — 여러 주를 마감할 때 앞 주가 먼저 커밋돼야 뒤 주의 판정이 그
 * 주를 마감된 기준선 · 전주로 본다. 외부 I/O 없이 같은 DB 만 읽고 쓴다.
 *
 * <p>재계산은 카운터를 누적하지 않고 원시 보고를 {@code GROUP BY} 로 다시 세어 덮어쓴다(멱등). 판정은 {@link AggregateRule} 이 하고, 현재 주는
 * {@link WeekState#IN_PROGRESS}, 마감은 {@link WeekState#CLOSED} 로 부른다.
 *
 * <p><b>마감된 주는 다시 세지 않는다.</b> 그 주에 마감된 행이 하나라도 있으면 수치를 건드리지 않는다 — 마감 뒤 파기(#155)로 원시 보고가 줄어도 익명
 * 집계는 그대로 남는다.
 */
@Component
@RequiredArgsConstructor
public class AggregateCommandProcessor {

    private final DistrictWeeklyAggregateRepositoryPort districtWeeklyAggregateRepositoryPort;
    private final AggregateReportQueryPort aggregateReportQueryPort;
    private final AggregateProperties aggregateProperties;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    /** 아직 보고를 받는 현재 주를 다시 센다 (참여 급변은 보지 않는다). */
    @Transactional
    public AggregateWeekResult recalculateInProgress(ReportWeek isoWeek) {
        return recalculate(isoWeek, WeekState.IN_PROGRESS);
    }

    /**
     * 지난 주를 마지막으로 다시 세고 같은 트랜잭션에서 마감한다 — 마지막 수치와 {@code finalized_at} 이 함께 커밋되거나 함께 롤백된다. 이미 마감된
     * 주면 다시 세지 않고, 혹시 남은 미마감 행만 그대로 마감한다.
     */
    @Transactional
    public AggregateWeekResult close(ReportWeek isoWeek) {
        AggregateWeekResult recalculated = recalculate(isoWeek, WeekState.CLOSED);
        int finalized = districtWeeklyAggregateRepositoryPort.finalizeWeek(isoWeek, now());
        return new AggregateWeekResult(isoWeek, WeekState.CLOSED, recalculated.reportedDistricts(), recalculated.inserted(), recalculated.updated(),
            finalized, recalculated.alreadyFinalized());
    }

    /**
     * {@code cutoff} 이전에 마감할 주를 오래된 순으로 고른다.
     * <ul>
     *   <li>마감 안 된 집계 행이 남은 주 (기간 제한 없음)</li>
     *   <li>원시 보고는 있는데 집계 행이 하나도 없는 주 — 배포 전부터 쌓인 보고, 한 주 내내 서비스가 내려가 재계산이 돌지 않은 주. 범위는
     *       {@code cutoff} 직전 {@code baseline-lookback-weeks + 1} 주다. 그보다 오래된 주는 어느 판정에도 쓰이지 않는다 — 기준선은 직전
     *       lookback 주에서만 고르고, 그 가장 오래된 주의 참여 급변 판정에 한 주 앞 행이 필요하다.</li>
     * </ul>
     *
     * @param cutoff 이 주부터는 아직 마감하지 않는다 (마감 유예가 지나지 않은 주)
     */
    @Transactional(readOnly = true)
    public List<ReportWeek> findWeeksToClose(ReportWeek cutoff) {
        Map<String, ReportWeek> weeks = new TreeMap<>();
        districtWeeklyAggregateRepositoryPort.findUnfinalizedWeeksBefore(cutoff).forEach(week -> weeks.put(week.value(), week));

        ReportWeek from = cutoff.minusWeeks(aggregateProperties.baselineLookbackWeeks() + 1);
        List<ReportWeek> reported = aggregateReportQueryPort.findReportedWeeks(from, cutoff);
        Set<ReportWeek> withRows = districtWeeklyAggregateRepositoryPort.findWeeksWithRows(reported);
        reported.stream().filter(week -> !withRows.contains(week)).forEach(week -> weeks.put(week.value(), week));
        // YYYY-Www 문자열 순서가 시간 순서다.
        return new ArrayList<>(weeks.values());
    }

    private AggregateWeekResult recalculate(ReportWeek isoWeek, WeekState weekState) {
        AggregateRule rule = aggregateProperties.toRule();
        LocalDateTime calculatedAt = now();
        Map<String, DistrictWeeklyAggregate> existing = districtWeeklyAggregateRepositoryPort.findAllByIsoWeek(isoWeek).stream()
            .collect(Collectors.toMap(DistrictWeeklyAggregate::districtCode, Function.identity()));
        if (existing.values().stream().anyMatch(DistrictWeeklyAggregate::finalized)) {
            return new AggregateWeekResult(isoWeek, weekState, 0, 0, 0, 0, true);
        }

        Map<String, AggregateCounts> counts = aggregateReportQueryPort.countByDistrict(isoWeek);
        Map<String, List<DistrictWeeklyAggregate>> history = districtWeeklyAggregateRepositoryPort.findAllByIsoWeekIn(lookbackWeeks(isoWeek, rule)).stream()
            .collect(Collectors.groupingBy(DistrictWeeklyAggregate::districtCode));

        // 결과에 없는 기존 행(동을 바꾼 수정 · 파기로 0건이 된 동)도 함께 돈다 — 건너뛰면 이전 수치가 남는다. 0건은 판정에서 LOW_SAMPLE 이 된다.
        Set<String> districtCodes = new TreeSet<>(counts.keySet());
        districtCodes.addAll(existing.keySet());
        int inserted = 0;
        int updated = 0;
        // 루프 안 단건 insert · update (coding-conventions §8-5 예외): 1단계 규모(동 수 × 10분 주기)에서는 단건으로 충분하다. 늘어나면 *BulkPort(JDBC
        // batch)로 바꾼다. 조회는 위에서 주 단위로 한 번씩 끝냈고, 스케줄러 경로라 사용자 요청 지연과 무관하다.
        for (String districtCode : districtCodes) {
            AggregateCalculation calculation = rule.calculate(districtCode, isoWeek, weekState, counts.getOrDefault(districtCode, AggregateCounts.ZERO),
                history.getOrDefault(districtCode, List.of()), calculatedAt);
            if (existing.containsKey(districtCode)) {
                updated += districtWeeklyAggregateRepositoryPort.updateCalculation(calculation);
            } else {
                districtWeeklyAggregateRepositoryPort.insert(DistrictWeeklyAggregate.newAggregate(snowflakeIdGenerator.generateId(), calculation));
                inserted++;
            }
        }
        return new AggregateWeekResult(isoWeek, weekState, counts.size(), inserted, updated, 0, false);
    }

    /**
     * 계산 · 마감 시각. Auditing({@code created_at}) · 어댑터의 {@code updated_at} 과 같은 기준(JVM 기본 시간대의 지역 시각, 배포 환경은
     * {@code -Duser.timezone=Asia/Seoul})으로 쓰되 순간은 {@link Clock} 빈에서 받는다 — 같은 행의 시각 컬럼끼리 시간대가 갈리지 않게 한다.
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    // 판정에 쓰는 직전 lookback 주 (기준선 후보 · 전주).
    private static List<ReportWeek> lookbackWeeks(ReportWeek isoWeek, AggregateRule rule) {
        List<ReportWeek> weeks = new ArrayList<>(rule.baselineLookbackWeeks());
        for (int back = 1; back <= rule.baselineLookbackWeeks(); back++) {
            weeks.add(isoWeek.minusWeeks(back));
        }
        return weeks;
    }
}
