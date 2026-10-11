package com.sneezecast.domainlayer.aggregate.application.service.processor;

import com.sneezecast.domainlayer.aggregate.application.exception.AggregateErrorCode;
import com.sneezecast.domainlayer.aggregate.application.exception.AggregateException;
import com.sneezecast.domainlayer.aggregate.application.info.AggregateWeekInfo;
import com.sneezecast.domainlayer.aggregate.application.info.DistrictWeeklyAggregateInfo;
import com.sneezecast.domainlayer.aggregate.application.info.DistrictWeeklyAggregateInfo.GroupRate;
import com.sneezecast.domainlayer.aggregate.application.info.DistrictWeeklyAggregateInfo.Measured;
import com.sneezecast.domainlayer.aggregate.application.port.out.AggregateDistrictQueryPort;
import com.sneezecast.domainlayer.aggregate.application.port.out.DistrictWeeklyAggregateRepositoryPort;
import com.sneezecast.domainlayer.aggregate.application.port.out.query.AggregateDistrictQueryResult;
import com.sneezecast.domainlayer.aggregate.application.service.AggregateProperties;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRate;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRule;
import com.sneezecast.domainlayer.aggregate.domain.model.DistrictWeeklyAggregate;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공개 집계 조회 (홈 · 지도). 집계 행만 읽고 원시 보고는 보지 않는다 (architecture-guide §6). 회원 · 보고자 정보가 없어 비회원도 같은 값을 받는다.
 *
 * <ul>
 *   <li><b>행이 없는 동</b>(그 주 보고가 한 번도 없던 동)은 INSUFFICIENT · LOW_SAMPLE · 참여 0 이다 (entity-design §2-2).</li>
 *   <li><b>INSUFFICIENT 면 수치를 담지 않는다</b> — 행에 숫자가 있어도 비율 · 증상 수 · 증상군 수치 · 변화를 뺀다. 참여자 수만 남긴다.</li>
 *   <li><b>증상군 변화</b>는 이번 주와 전주가 모두 수치가 있는 단계일 때만 계산한다. 진행 중인 주(참여가 쌓이는 중)와 마감된 전주를 견주므로 주 초반에는
 *       변화가 크게 흔들릴 수 있다 — 표본 하한(100명)을 넘어야 수치가 나오는 것으로 그 폭을 줄인다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AggregateQueryProcessor {

    /** 조회할 수 있는 가장 오래된 주 = 현재 주 − 51주. 원시 보고 보관(52주, entity-design §8)과 같은 창이다. */
    public static final int QUERYABLE_PAST_WEEKS = 51;

    private final DistrictWeeklyAggregateRepositoryPort districtWeeklyAggregateRepositoryPort;
    private final AggregateDistrictQueryPort aggregateDistrictQueryPort;
    private final AggregateProperties aggregateProperties;
    private final Clock clock;

    /**
     * 동네 하나의 한 주. 폐지된 코드도 오류가 아니다({@code active=false}) — 행정동 조회(#111)와 같은 관례다.
     *
     * @param week {@code YYYY-Www} 형식이 확인된 주. null 이면 현재 주(KST)
     * @throws AggregateException 주를 조회할 수 없으면 {@code WEEK_NOT_QUERYABLE}, 코드가 없으면 {@code DISTRICT_NOT_FOUND}
     */
    public AggregateWeekInfo getDistrictWeek(String districtCode, String week) {
        ReportWeek isoWeek = resolveWeek(week);
        return weekOf(isoWeek, List.of(findDistrict(districtCode)));
    }

    /**
     * 시군구 하나의 현행 행정동 전부의 한 주 (지도). {@code includeCode}(찾은 동네)는 그 시군구 밖이거나 폐지된 코드여도 <b>반드시 담는다</b> — 목록에 없으면
     * 맨 앞에 더한다. 시군구에 현행 동이 없고 {@code includeCode} 도 없으면 빈 목록이다.
     *
     * @throws AggregateException 주를 조회할 수 없으면 {@code WEEK_NOT_QUERYABLE}, {@code includeCode} 가 없는 코드면 {@code DISTRICT_NOT_FOUND}
     */
    public AggregateWeekInfo getSigunguWeek(String sigunguCode, String includeCode, String week) {
        ReportWeek isoWeek = resolveWeek(week);
        List<AggregateDistrictQueryResult> districts = aggregateDistrictQueryPort.findActiveBySigunguCode(sigunguCode);
        if (includeCode != null && districts.stream().noneMatch(district -> district.code().equals(includeCode))) {
            List<AggregateDistrictQueryResult> withIncluded = new ArrayList<>(districts.size() + 1);
            withIncluded.add(findDistrict(includeCode));
            withIncluded.addAll(districts);
            districts = withIncluded;
        }
        return weekOf(isoWeek, districts);
    }

    /**
     * 생략하면 현재 주. 현재 주(KST, {@code Clock} 빈 — 보고 주와 같은 정의) ~ {@value #QUERYABLE_PAST_WEEKS}주 전만 받는다. 미래 주 · 그보다 오래된 주 ·
     * 그 해에 없는 주(53주가 없는 해의 W53)는 {@code WEEK_NOT_QUERYABLE} 이고 메시지에 조회할 수 있는 범위를 싣는다.
     */
    ReportWeek resolveWeek(String week) {
        ReportWeek current = ReportWeek.of(clock.instant());
        if (week == null) {
            return current;
        }
        ReportWeek oldest = current.minusWeeks(QUERYABLE_PAST_WEEKS);
        ReportWeek requested;
        try {
            requested = ReportWeek.parse(week);
        } catch (IllegalArgumentException exception) {
            throw new AggregateException(AggregateErrorCode.WEEK_NOT_QUERYABLE, oldest.value(), current.value());
        }
        // YYYY-Www 문자열 정렬이 시간 순이다 (ReportWeek 문서).
        if (requested.value().compareTo(oldest.value()) < 0 || requested.value().compareTo(current.value()) > 0) {
            throw new AggregateException(AggregateErrorCode.WEEK_NOT_QUERYABLE, oldest.value(), current.value());
        }
        return requested;
    }

    private AggregateDistrictQueryResult findDistrict(String districtCode) {
        return aggregateDistrictQueryPort.findByCode(districtCode).orElseThrow(() -> new AggregateException(AggregateErrorCode.DISTRICT_NOT_FOUND));
    }

    private AggregateWeekInfo weekOf(ReportWeek isoWeek, List<AggregateDistrictQueryResult> districts) {
        AggregateRule rule = aggregateProperties.toRule();
        ReportWeek previousWeek = isoWeek.previous();
        List<String> codes = districts.stream().map(AggregateDistrictQueryResult::code).toList();
        // 동네마다 단건으로 읽지 않고 이번 주 · 전주 행을 한 번에 읽는다 (coding-conventions §8-5).
        Map<String, DistrictWeeklyAggregate> currentRows = new HashMap<>();
        Map<String, DistrictWeeklyAggregate> previousRows = new HashMap<>();
        for (DistrictWeeklyAggregate row : districtWeeklyAggregateRepositoryPort.findAllByDistrictCodeInAndIsoWeekIn(codes, List.of(isoWeek, previousWeek))) {
            (row.isoWeek().equals(isoWeek) ? currentRows : previousRows).put(row.districtCode(), row);
        }
        List<DistrictWeeklyAggregateInfo> infos = districts.stream()
            .map(district -> toInfo(district, currentRows.get(district.code()), previousRows.get(district.code()), rule))
            .toList();
        return new AggregateWeekInfo(isoWeek, districtWeeklyAggregateRepositoryPort.existsFinalizedRow(isoWeek), rule.minSample(), rule.slightDeltaPp(),
            rule.highDeltaPp(), infos);
    }

    private static DistrictWeeklyAggregateInfo toInfo(AggregateDistrictQueryResult district, DistrictWeeklyAggregate row, DistrictWeeklyAggregate previous,
        AggregateRule rule) {
        DistrictWeeklyAggregateInfo.DistrictWeeklyAggregateInfoBuilder info = DistrictWeeklyAggregateInfo.builder()
            .code(district.code())
            .name(district.name())
            .sigungu(district.sigungu())
            .active(district.active());
        if (row == null) {
            return info.level(AggregateLevel.INSUFFICIENT).insufficientReason(InsufficientReason.LOW_SAMPLE).participantCount(0).build();
        }
        info.level(row.level()).insufficientReason(row.insufficientReason()).participantCount(row.participantCount()).calculatedAt(row.calculatedAt());
        if (!row.measured()) {
            return info.build();
        }
        // 수치가 있는 단계는 참여 ≥ 최소 표본(> 0)이고 기준선 두 수가 있다 (AggregateJudgement 불변식).
        boolean comparable = previous != null && previous.measured();
        List<GroupRate> groups = Arrays.stream(SymptomGroup.values())
            .map(group -> new GroupRate(group, AggregateRate.percent(row.groupCount(group), row.participantCount()),
                comparable ? rule.trend(row.groupCount(group), row.participantCount(), previous.groupCount(group), previous.participantCount()) : null))
            .toList();
        return info.measured(new Measured(row.symptomaticCount(), AggregateRate.percent(row.symptomaticCount(), row.participantCount()),
            AggregateRate.percent(row.baselineSymptomaticCount(), row.baselineParticipantCount()), groups)).build();
    }
}
