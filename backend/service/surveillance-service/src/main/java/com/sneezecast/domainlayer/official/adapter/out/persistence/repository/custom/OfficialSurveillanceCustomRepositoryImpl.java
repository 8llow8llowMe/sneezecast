package com.sneezecast.domainlayer.official.adapter.out.persistence.repository.custom;

import static com.sneezecast.domainlayer.official.adapter.out.persistence.entity.QOfficialSurveillanceEntity.officialSurveillanceEntity;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.DateExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sneezecast.domainlayer.official.adapter.out.persistence.entity.OfficialSurveillanceEntity;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;

/**
 * 계열별 최신 기간 조회 (QueryDSL). <b>쿼리는 두 번이다</b>:
 *
 * <ol>
 *   <li>계열마다 {@code max(period_start)} — {@code GROUP BY} 로 계열 수(백여 개)만큼만 돌아온다.</li>
 *   <li>그 기간들의 행 — 프로그램마다 {@code period_start IN (...)} 로 읽고({@code idx_official_surveillance_program_period_start}),
 *       자기 계열의 최신 기간이 아닌 행(같은 주에 걸린 다른 계열의 행)은 여기서 버린다.</li>
 * </ol>
 * 계열마다 {@code period_start = (SELECT max ...)} 상관 서브쿼리를 걸면 한 번에 끝나지만 행마다 서브쿼리가 돌아, 공개 화면이 부를 때마다 표 전체를
 * 계열 수만큼 훑는다. 튜플 {@code IN} 은 JPQL · H2 · MySQL 이 함께 받는 모양이 아니라 쓰지 않는다.
 *
 * <p>계열 키에는 연령대를 넣지 않는다 — 인플루엔자처럼 연령대별 행만 오는 계열은 연령대 행을 묶어 한 주로 본다.
 */
@RequiredArgsConstructor
public class OfficialSurveillanceCustomRepositoryImpl implements OfficialSurveillanceCustomRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<OfficialSurveillanceEntity> findLatestWeekly(Map<OfficialProgram, LocalDate> periodStartLimits) {
        DateExpression<LocalDate> latestStart = officialSurveillanceEntity.periodStart.max();
        List<Tuple> latestRows = queryFactory
            .select(officialSurveillanceEntity.source, officialSurveillanceEntity.program, officialSurveillanceEntity.diseaseKey,
                officialSurveillanceEntity.metric, officialSurveillanceEntity.regionLevel, officialSurveillanceEntity.regionCode, latestStart)
            .from(officialSurveillanceEntity)
            .where(officialSurveillanceEntity.periodType.eq(OfficialPeriodType.WEEK), withinLimits(periodStartLimits))
            .groupBy(officialSurveillanceEntity.source, officialSurveillanceEntity.program, officialSurveillanceEntity.diseaseKey,
                officialSurveillanceEntity.metric, officialSurveillanceEntity.regionLevel, officialSurveillanceEntity.regionCode)
            .fetch();
        if (latestRows.isEmpty()) {
            return List.of();
        }

        Map<SeriesKey, LocalDate> latestBySeries = new HashMap<>();
        Map<OfficialProgram, Set<LocalDate>> startsByProgram = new EnumMap<>(OfficialProgram.class);
        for (Tuple latest : latestRows) {
            SeriesKey key = new SeriesKey(latest.get(officialSurveillanceEntity.source), latest.get(officialSurveillanceEntity.program),
                latest.get(officialSurveillanceEntity.diseaseKey), latest.get(officialSurveillanceEntity.metric),
                latest.get(officialSurveillanceEntity.regionLevel), latest.get(officialSurveillanceEntity.regionCode));
            LocalDate start = latest.get(latestStart);
            latestBySeries.put(key, start);
            startsByProgram.computeIfAbsent(key.program(), program -> new TreeSet<>()).add(start);
        }

        BooleanBuilder inLatestWeeks = new BooleanBuilder();
        startsByProgram.forEach((program, starts) ->
            inLatestWeeks.or(officialSurveillanceEntity.program.eq(program).and(officialSurveillanceEntity.periodStart.in(starts))));
        return queryFactory
            .selectFrom(officialSurveillanceEntity)
            .where(officialSurveillanceEntity.periodType.eq(OfficialPeriodType.WEEK), inLatestWeeks)
            .fetch()
            .stream()
            .filter(row -> row.getPeriodStart().equals(latestBySeries.get(SeriesKey.of(row))))
            .toList();
    }

    /** 제한이 걸린 프로그램은 기간 시작일이 한도 이하인 행만, 나머지 프로그램은 모두. 제한이 없으면 조건을 걸지 않는다(null — QueryDSL 이 건너뛴다). */
    private static Predicate withinLimits(Map<OfficialProgram, LocalDate> periodStartLimits) {
        if (periodStartLimits.isEmpty()) {
            return null;
        }
        BooleanBuilder within = new BooleanBuilder(officialSurveillanceEntity.program.notIn(periodStartLimits.keySet()));
        periodStartLimits.forEach((program, limit) ->
            within.or(officialSurveillanceEntity.program.eq(program).and(officialSurveillanceEntity.periodStart.loe(limit))));
        return within;
    }

    private record SeriesKey(
        OfficialSource source,
        OfficialProgram program,
        String diseaseKey,
        OfficialMetric metric,
        OfficialRegionLevel regionLevel,
        String regionCode
    ) {

        static SeriesKey of(OfficialSurveillanceEntity row) {
            return new SeriesKey(row.getSource(), row.getProgram(), row.getDiseaseKey(), row.getMetric(), row.getRegionLevel(), row.getRegionCode());
        }
    }
}
