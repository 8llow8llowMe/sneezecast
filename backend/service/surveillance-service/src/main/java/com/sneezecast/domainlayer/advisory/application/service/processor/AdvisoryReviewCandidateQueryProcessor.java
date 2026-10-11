package com.sneezecast.domainlayer.advisory.application.service.processor;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.AdvisoryState;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.Candidate;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.GroupCount;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.Measured;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryAggregateQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryDistrictQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryCandidateAggregateQueryResult;
import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryDistrictQueryResult;
import com.sneezecast.domainlayer.advisory.application.service.ReviewCandidateProperties;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.enums.ReviewSignal;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.advisory.domain.model.ReviewCandidateRule;
import com.sneezecast.domainlayer.advisory.domain.model.ReviewCandidateRule.WeekCounts;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRate;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 운영자 예방 안내 검토 후보 (#266, entity-design §2-2 "검토 후보"). 신호는 조회 때 집계 행으로 계산하고 저장하지 않는다(후보 적재 {@code review_signal}
 * 은 2단계). 트랜잭션은 호출자(WebFacade, 읽기 전용)가 건다.
 *
 * <p><b>후보</b>는 그 주 집계 행이 있는 동네 중 ① 신호({@link ReviewCandidateRule})가 하나라도 있거나 ② 신호가 없어도 그 주에 안내가 있는 동네다(이어서
 * 검토 · 발행 상태 확인). 자료 부족 동네도 급증 · 반복 신호가 있으면 후보이고(2026-10-11 결정) 수치 없이 참여 · 전주 참여 · 수정 수만 담는다. 참여 부족
 * (LOW_SAMPLE) 칸은 신호가 나지 않는다.
 *
 * <p><b>조회 수</b>(coding-conventions §8-5 — 동네 수만큼 단건 조회하지 않는다): 집계 [그 주, 전주] 1 · 안내 1(그 주 행이 마감됐고 자료 부족이 아닌 동네
 * 코드 IN × 그 주 — 안내는 그런 근거 집계 행으로만 만들어지고 마감 행은 바뀌지 않으므로 다른 동네에는 없다) · 행정동 1(후보 코드 IN). 마감 여부는 읽은 행으로
 * 정한다(추가 조회 없음).
 */
@Slf4j
@Component
public class AdvisoryReviewCandidateQueryProcessor {

    /** 검토 중으로 보는 안내 상태 — 발행 전 단계(§2-5). */
    private static final Set<AdvisoryStatus> IN_REVIEW = EnumSet.of(AdvisoryStatus.DRAFT, AdvisoryStatus.AI_DRAFTED, AdvisoryStatus.EDITED,
        AdvisoryStatus.APPROVED);

    /**
     * 정렬 — ① 신호가 있는 후보 먼저(신호 없이 안내만 있는 동네는 뒤), ② 단계가 높은 것부터(많이 늘었어요 → 조금 늘었어요 → 평소 수준 → 자료 부족 — 자료 부족은
     * 초안을 만들 수 없다), ③ 행정동 코드 오름차순. 화면은 첫 후보를 먼저 연다.
     */
    static final Comparator<Candidate> CANDIDATE_ORDER = Comparator.comparing((Candidate candidate) -> candidate.signals().isEmpty())
        .thenComparingInt(candidate -> levelRank(candidate.level()))
        .thenComparing(Candidate::districtCode);

    private final AdvisoryAggregateQueryPort advisoryAggregateQueryPort;
    private final AdvisoryRepositoryPort advisoryRepositoryPort;
    private final AdvisoryDistrictQueryPort advisoryDistrictQueryPort;
    private final ReviewCandidateRule rule;
    private final Clock clock;

    public AdvisoryReviewCandidateQueryProcessor(AdvisoryAggregateQueryPort advisoryAggregateQueryPort, AdvisoryRepositoryPort advisoryRepositoryPort,
        AdvisoryDistrictQueryPort advisoryDistrictQueryPort, ReviewCandidateProperties reviewCandidateProperties, Clock clock) {
        this.advisoryAggregateQueryPort = advisoryAggregateQueryPort;
        this.advisoryRepositoryPort = advisoryRepositoryPort;
        this.advisoryDistrictQueryPort = advisoryDistrictQueryPort;
        this.rule = reviewCandidateProperties.toRule();
        this.clock = clock;
    }

    /**
     * 한 주의 검토 후보.
     *
     * @param week {@code YYYY-Www} 형식이 확인된 주. null 이면 현재 주 − 1(마감될 수 있는 가장 최근 주)
     * @throws AdvisoryException 주를 조회할 수 없으면 {@code WEEK_NOT_QUERYABLE}(400)
     */
    public ReviewCandidateListInfo getCandidates(String week) {
        ReportWeek isoWeek = resolveWeek(week);
        ReportWeek previousWeek = isoWeek.previous();
        List<AdvisoryCandidateAggregateQueryResult> rows = advisoryAggregateQueryPort.findAllByIsoWeekIn(List.of(isoWeek, previousWeek));
        Map<String, AdvisoryCandidateAggregateQueryResult> current = byDistrict(rows, isoWeek);
        Map<String, AdvisoryCandidateAggregateQueryResult> previous = byDistrict(rows, previousWeek);
        boolean finalized = current.values().stream().anyMatch(AdvisoryCandidateAggregateQueryResult::finalized);

        // 안내는 마감됐고 자료 부족이 아닌 집계 행으로만 만들어지고(초안 전제, entity-design §2-3) 마감된 행은 다시 계산되지 않는다 — 그런 동네만 묻는다.
        List<String> draftableCodes = current.values().stream()
            .filter(AdvisoryReviewCandidateQueryProcessor::draftable)
            .map(AdvisoryCandidateAggregateQueryResult::districtCode)
            .toList();
        Map<String, List<Advisory>> advisories = advisoryRepositoryPort.findAllByDistrictCodeInAndIsoWeek(draftableCodes, isoWeek).stream()
            .collect(Collectors.groupingBy(Advisory::districtCode));

        List<Picked> picked = new ArrayList<>();
        for (AdvisoryCandidateAggregateQueryResult row : current.values()) {
            AdvisoryCandidateAggregateQueryResult previousRow = previous.get(row.districtCode());
            Set<ReviewSignal> signals = rule.signals(toCounts(row), previousRow == null ? null : toCounts(previousRow));
            Optional<AdvisoryState> advisory = selectAdvisory(advisories.getOrDefault(row.districtCode(), List.of()));
            if (!signals.isEmpty() || advisory.isPresent()) {
                picked.add(new Picked(row, previousRow, List.copyOf(signals), advisory.orElse(null)));
            }
        }

        Map<String, AdvisoryDistrictQueryResult> districts = advisoryDistrictQueryPort.findAllByCodeIn(picked.stream().map(p -> p.row().districtCode()).toList())
            .stream()
            .collect(Collectors.toMap(AdvisoryDistrictQueryResult::code, Function.identity()));
        List<Candidate> candidates = picked.stream()
            .map(p -> toCandidate(p, districts.get(p.row().districtCode())))
            .sorted(CANDIDATE_ORDER)
            .toList();
        return new ReviewCandidateListInfo(isoWeek, finalized, rule.surgeChangePercent(), rule.repeatRevisedPercent(), candidates);
    }

    /**
     * 생략하면 현재 주 − 1 이다 — 현재 주는 아직 보고를 받는 중이라 마감 전이고(초안을 만들 수 없다), 지난 주가 마감될 수 있는 가장 최근 주다. 받을 수 있는 주는
     * 현재 주(KST, {@code Clock} 빈) ~ {@value AdvisoryPublicQueryProcessor#QUERYABLE_PAST_WEEKS}주 전으로 공개 동네 안내 · 공개 집계와 같은 창이다.
     * 미래 주 · 그보다 오래된 주 · 그 해에 없는 주(53주가 없는 해의 W53)는 {@code WEEK_NOT_QUERYABLE} 이고 메시지에 범위를 싣는다.
     */
    ReportWeek resolveWeek(String week) {
        ReportWeek current = ReportWeek.of(clock.instant());
        return week == null ? current.previous() : AdvisoryPublicQueryProcessor.resolveQueryableWeek(week, current);
    }

    /**
     * 동네 · 주의 안내 중 후보에 보일 하나 — ① 발행 중(PUBLISHED, 여럿이면 가장 최근 발행 {@link Advisory#PUBLICATION_ORDER}) ② 검토 중(DRAFT · AI_DRAFTED ·
     * EDITED · APPROVED 중 가장 최근 id — Snowflake 라 가장 늦게 만든 것) ③ 가장 최근 철회(RETRACTED, 철회 시각 · id 순) ④ 없음.
     */
    static Optional<AdvisoryState> selectAdvisory(List<Advisory> advisories) {
        Optional<Advisory> published = advisories.stream().filter(a -> a.status() == AdvisoryStatus.PUBLISHED).max(Advisory.PUBLICATION_ORDER);
        Optional<Advisory> selected = published
            .or(() -> advisories.stream().filter(a -> IN_REVIEW.contains(a.status())).max(Comparator.comparingLong(Advisory::id)))
            .or(() -> advisories.stream().filter(a -> a.status() == AdvisoryStatus.RETRACTED).max(AdvisoryPublicQueryProcessor.RETRACTION_ORDER));
        return selected.map(advisory -> new AdvisoryState(advisory.id(), advisory.status(), advisory.version()));
    }

    // UK (district_code, iso_week) 라 동네마다 한 행이다. 어기면 조회가 틀린 것이라 조용히 고르지 않는다.
    private static Map<String, AdvisoryCandidateAggregateQueryResult> byDistrict(List<AdvisoryCandidateAggregateQueryResult> rows, ReportWeek isoWeek) {
        return rows.stream()
            .filter(row -> row.isoWeek().equals(isoWeek))
            .collect(Collectors.toMap(AdvisoryCandidateAggregateQueryResult::districtCode, Function.identity()));
    }

    /** 이 칸으로 초안을 만들 수 있는지 — 마감됐고 자료 부족이 아니다(초안 생성 전제 {@code ADVISORY_003} · {@code 004} 와 같다). */
    private static boolean draftable(AdvisoryCandidateAggregateQueryResult row) {
        return row.finalized() && row.level() != AggregateLevel.INSUFFICIENT;
    }

    private static WeekCounts toCounts(AdvisoryCandidateAggregateQueryResult row) {
        return new WeekCounts(row.participantCount(), row.revisedReportCount(), row.level(), row.insufficientReason());
    }

    private static Candidate toCandidate(Picked picked, AdvisoryDistrictQueryResult district) {
        AdvisoryCandidateAggregateQueryResult row = picked.row();
        if (district == null) {
            // district 행은 지우지 않으므로(폐지는 valid_to_year) 집계 행의 코드는 늘 있어야 한다 — 적재 사고다. 이름 없이 보이고 남긴다.
            log.warn("review candidate district missing districtCode={}", row.districtCode());
        }
        boolean measured = row.level() != AggregateLevel.INSUFFICIENT;
        return Candidate.builder()
            .districtCode(row.districtCode())
            .districtName(district == null ? null : district.name())
            .sigungu(district == null ? null : district.sigungu())
            .active(district != null && district.active())
            .signals(picked.signals())
            .level(row.level())
            .insufficientReason(row.insufficientReason())
            .draftable(draftable(row))
            .participantCount(row.participantCount())
            .previousParticipantCount(picked.previousRow() == null ? null : picked.previousRow().participantCount())
            .revisedReportCount(row.revisedReportCount())
            .measured(measured ? toMeasured(row) : null)
            .advisory(picked.advisory())
            .build();
    }

    /** 수치가 있는 단계는 기준선이 있고 참여자가 최소 표본 이상이다(집계 행 불변식) — 분모가 0 이 아니다. */
    private static Measured toMeasured(AdvisoryCandidateAggregateQueryResult row) {
        BigDecimal symptomRate = AggregateRate.percent(row.symptomaticCount(), row.participantCount());
        BigDecimal baselineRate = AggregateRate.percent(row.baselineSymptomaticCount(), row.baselineParticipantCount());
        List<GroupCount> groups = Arrays.stream(SymptomGroup.values())
            .map(group -> {
                int count = groupCount(row, group);
                return new GroupCount(group, count, AggregateRate.percent(count, row.participantCount()));
            })
            .toList();
        return new Measured(row.symptomaticCount(), symptomRate, baselineRate, symptomRate.subtract(baselineRate), groups);
    }

    // 증상군이 늘면 이 switch 가 컴파일에서 실패해 집계 값을 함께 늘리게 한다(DistrictWeeklyAggregate#groupCount 와 같다).
    private static int groupCount(AdvisoryCandidateAggregateQueryResult row, SymptomGroup group) {
        return switch (group) {
            case RESPIRATORY -> row.respiratoryCount();
            case ENTERIC -> row.entericCount();
        };
    }

    private static int levelRank(AggregateLevel level) {
        return switch (level) {
            case HIGH -> 0;
            case SLIGHT -> 1;
            case NORMAL -> 2;
            case INSUFFICIENT -> 3;
        };
    }

    private record Picked(
        AdvisoryCandidateAggregateQueryResult row,
        AdvisoryCandidateAggregateQueryResult previousRow,
        List<ReviewSignal> signals,
        AdvisoryState advisory
    ) {

    }
}
