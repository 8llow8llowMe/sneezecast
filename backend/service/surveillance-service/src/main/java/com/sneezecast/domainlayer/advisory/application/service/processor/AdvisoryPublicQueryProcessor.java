package com.sneezecast.domainlayer.advisory.application.service.processor;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.info.RegionAdvisoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.RegionAdvisoryInfo.CitedGroup;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryDistrictQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRate;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 공개 동네 안내 (#215). 동네 · 주의 발행됨(PUBLISHED) · 철회됨(RETRACTED) 안내만 읽어 셋 중 하나로 정한다 — 초안 · 수정 · 승인 단계는 저장 포트가 내주지
 * 않는다. 회원 · 운영자 정보가 없어 비회원도 같은 값을 받는다. 트랜잭션은 호출자(WebFacade, 읽기 전용)가 건다.
 *
 * <ul>
 *   <li><b>발행 중</b> — PUBLISHED 가 있으면 그 안내와, {@code corrects_advisory_id} 로 이은 이전 안내 사슬의 정정 시각(최근 것부터). 철회 표시는 주지
 *       않는다 — 철회 뒤 새 안내가 발행됐으면 새 안내만 보인다.</li>
 *   <li><b>철회됨</b> — PUBLISHED 가 없고, 다른 안내가 정정하지 않은 RETRACTED 가 있으면 그중 가장 최근 철회 시각만. 정정으로 내려간 안내(다른 공개 안내의
 *       {@code corrects_advisory_id} 가 가리키는 안내)는 철회 표시가 아니다 — 정정한 안내 쪽의 정정 이력으로 보인다.</li>
 *   <li><b>안내 없음</b> — 그 밖. empty.</li>
 * </ul>
 *
 * <p>같은 동네 · 주에 발행 중인 안내는 하나다(entity-design §2-3, 전이가 잠금으로 지킨다). 조회는 잠그지 않으므로 규칙이 깨진 행이 있어도 500 을 내지 않고
 * 가장 최근 발행 하나({@link Advisory#PUBLICATION_ORDER})를 고르고 WARN 을 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryPublicQueryProcessor {

    /** 조회할 수 있는 가장 오래된 주 = 현재 주 − 51주. 공개 집계(#212, {@code AggregateQueryProcessor})와 같은 창이다. */
    public static final int QUERYABLE_PAST_WEEKS = 51;

    /** 철회 표시 후보 순서 — 철회 시각, 같으면 id(Snowflake, 시간 순). 테스트가 같은 시각의 선택을 고정하려고 패키지 범위로 둔다. */
    static final Comparator<Advisory> RETRACTION_ORDER = Comparator.comparing(Advisory::retractedAt).thenComparingLong(Advisory::id);

    private final AdvisoryRepositoryPort advisoryRepositoryPort;
    private final AdvisoryDistrictQueryPort advisoryDistrictQueryPort;
    private final Clock clock;

    /**
     * 동네 하나 · 주 하나의 공개 안내. 폐지된 코드도 오류가 아니다(그 주에 발행된 안내가 있으면 그대로 보인다).
     *
     * @param week {@code YYYY-Www} 형식이 확인된 주 (필수)
     * @return 발행 중인 안내 또는 철회 표시. 안내가 없으면 empty
     * @throws AdvisoryException 주를 조회할 수 없으면 {@code WEEK_NOT_QUERYABLE}, 코드가 없으면 {@code DISTRICT_NOT_FOUND}
     */
    public Optional<RegionAdvisoryInfo> getRegionAdvisory(String districtCode, String week) {
        ReportWeek isoWeek = resolveWeek(week);
        if (!advisoryDistrictQueryPort.existsByCode(districtCode)) {
            throw new AdvisoryException(AdvisoryErrorCode.DISTRICT_NOT_FOUND);
        }
        // 저장 포트가 공개 상태만 주지만, 공개 여부를 정하는 곳이 여기임을 분명히 하려고 상태로 한 번 더 가른다.
        List<Advisory> advisories = advisoryRepositoryPort.findAllPublicByDistrictCodeAndIsoWeek(districtCode, isoWeek);
        List<Advisory> published = advisories.stream().filter(advisory -> advisory.status() == AdvisoryStatus.PUBLISHED).toList();
        List<Advisory> retracted = advisories.stream().filter(advisory -> advisory.status() == AdvisoryStatus.RETRACTED).toList();

        if (!published.isEmpty()) {
            if (published.size() > 1) {
                log.warn("advisory multiple published districtCode={} isoWeek={} count={}", districtCode, isoWeek, published.size());
            }
            Advisory current = published.stream().max(Advisory.PUBLICATION_ORDER).orElseThrow();
            return Optional.of(RegionAdvisoryInfo.published(districtCode, isoWeek, toPublished(current, correctionTimes(current, retracted))));
        }
        // 상태로 거른 공개 안내(발행됨 · 철회됨)가 정정한 안내만 "정정됨" 이다 — 여기서는 발행 중인 안내가 없으므로 철회된 안내끼리의 정정이다.
        Set<Long> corrected = Stream.concat(published.stream(), retracted.stream())
            .map(Advisory::correctsAdvisoryId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        return retracted.stream()
            .filter(advisory -> !corrected.contains(advisory.id()))
            .max(RETRACTION_ORDER)
            .map(advisory -> RegionAdvisoryInfo.retracted(districtCode, isoWeek, advisory.retractedAt()));
    }

    /**
     * 생략할 수 없다. 현재 주(KST, {@code Clock} 빈 — 보고 주와 같은 정의) ~ {@value #QUERYABLE_PAST_WEEKS}주 전만 받는다. 미래 주 · 그보다 오래된 주 · 그 해에
     * 없는 주(53주가 없는 해의 W53)는 {@code WEEK_NOT_QUERYABLE} 이고 메시지에 조회할 수 있는 범위를 싣는다.
     */
    ReportWeek resolveWeek(String week) {
        ReportWeek current = ReportWeek.of(clock.instant());
        ReportWeek oldest = current.minusWeeks(QUERYABLE_PAST_WEEKS);
        ReportWeek requested;
        try {
            requested = ReportWeek.parse(week);
        } catch (IllegalArgumentException exception) {
            throw new AdvisoryException(AdvisoryErrorCode.WEEK_NOT_QUERYABLE, oldest.value(), current.value());
        }
        // YYYY-Www 문자열 정렬이 시간 순이다 (ReportWeek 문서).
        if (requested.value().compareTo(oldest.value()) < 0 || requested.value().compareTo(current.value()) > 0) {
            throw new AdvisoryException(AdvisoryErrorCode.WEEK_NOT_QUERYABLE, oldest.value(), current.value());
        }
        return requested;
    }

    /**
     * {@code current} 에서 {@code corrects_advisory_id} 를 따라 이전 안내로 간다. 이은 고리마다 정정한 쪽의 발행 시각이 정정 시각이고, 거꾸로 따라가므로
     * 최근 것부터 쌓인다. 정정은 같은 동네 · 주 · 근거 집계로 만들므로(entity-design §2-3) 이미 읽은 같은 동네 · 주의 철회된 안내 안에서만 찾는다 — 고리마다
     * 단건 조회하지 않는다. 가리키는 안내가 없거나(철회되지 않음 · 다른 동네 · 주) 고리가 돌면 거기서 멈추고 WARN 을 남긴다.
     */
    private static List<LocalDateTime> correctionTimes(Advisory current, List<Advisory> retracted) {
        Map<Long, Advisory> retractedById = retracted.stream().collect(Collectors.toMap(Advisory::id, Function.identity()));
        List<LocalDateTime> correctedAts = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(current.id());
        Advisory link = current;
        while (link.correctsAdvisoryId() != null) {
            Advisory previous = retractedById.get(link.correctsAdvisoryId());
            if (previous == null || !visited.add(previous.id())) {
                log.warn("advisory correction chain broken advisoryId={} correctsAdvisoryId={} cycle={}", link.id(), link.correctsAdvisoryId(),
                    previous != null);
                break;
            }
            correctedAts.add(link.publishedAt());
            link = previous;
        }
        return correctedAts;
    }

    /** 인용 수치는 발행 시점 고정값이다. 수치가 있는 단계(자료 부족 아님)라 참여자 수는 최소 표본 이상이고 0 이 아니다. */
    private static RegionAdvisoryInfo.Published toPublished(Advisory advisory, List<LocalDateTime> correctedAts) {
        AdvisoryCitation citation = advisory.citation();
        List<CitedGroup> groups = Arrays.stream(SymptomGroup.values())
            .map(group -> new CitedGroup(group, citation.groupCount(group), AggregateRate.percent(citation.groupCount(group), citation.participantCount())))
            .toList();
        return new RegionAdvisoryInfo.Published(advisory.title(), advisory.body(), advisory.publishedAt(), citation.level(), citation.participantCount(),
            citation.symptomaticCount(), AggregateRate.percent(citation.symptomaticCount(), citation.participantCount()), groups, correctedAts);
    }
}
