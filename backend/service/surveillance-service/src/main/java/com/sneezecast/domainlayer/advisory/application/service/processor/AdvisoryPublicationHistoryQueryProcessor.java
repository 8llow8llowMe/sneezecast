package com.sneezecast.domainlayer.advisory.application.service.processor;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryHistoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.PublicationHistoryListInfo;
import com.sneezecast.domainlayer.advisory.application.info.PublicationHistoryListInfo.Entry;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryDistrictQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryHistoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryDistrictQueryResult;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 운영자 발행 이력 (#267, A03). 요청한 주로 끝나는 {@value #WEEKS}주의 발행됨(PUBLISHED) · 철회됨(RETRACTED) 안내를 발행 시각 최근 것부터 — 동 이름 ·
 * 인용 수치 · 정정 관계(양방향) · 전 이력과 함께 돌려준다. 초안 · 수정 · 승인 단계는 발행 이력이 아니다(검토 후보 · 상세로 본다). 트랜잭션은 호출자
 * (WebFacade, 읽기 전용)가 건다 — 안내 · 이력 · 행정동을 한 스냅샷에서 읽는다.
 *
 * <p><b>정정 관계</b>: {@code correctsAdvisoryId}(이 안내가 정정한 이전 안내)는 저장된 값이고, {@code correctedByAdvisoryId}(이 안내를 정정한 새 안내)는 같은
 * 목록에서 거꾸로 이어 만든다 — 정정은 같은 동네 · 주로 만들고 새 안내는 만들어지자마자 발행되므로(entity-design §2-3) 이전 안내가 목록에 있으면 새 안내도
 * 같은 주라 늘 목록에 있다.
 *
 * <p><b>조회 수</b>(coding-conventions §8-5 — 안내 수만큼 단건 조회하지 않는다): 안내 1(주 IN × PUBLISHED · RETRACTED — {@code iso_week} 인덱스가 없어
 * 전체 스캔, 1단계 규모에서 그대로) · 이력 1(안내 ID IN, {@code idx_advisory_history_advisory_id}) · 행정동 1(코드 IN, {@code uk_district_code}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryPublicationHistoryQueryProcessor {

    /** 한 번에 보는 주 수 — 시안 A03 의 "최근 4주". 끝 주를 포함한다. */
    public static final int WEEKS = 4;

    /** 목록 순서 — 발행 시각 최근 것부터, 같으면 id 큰 것(Snowflake — 나중에 만든 것, 같은 순간의 정정이면 새 안내)부터. */
    static final Comparator<Advisory> LIST_ORDER = Advisory.PUBLICATION_ORDER.reversed();

    private final AdvisoryRepositoryPort advisoryRepositoryPort;
    private final AdvisoryHistoryRepositoryPort advisoryHistoryRepositoryPort;
    private final AdvisoryDistrictQueryPort advisoryDistrictQueryPort;
    private final Clock clock;

    /**
     * 끝 주로 끝나는 {@value #WEEKS}주의 발행 이력.
     *
     * @param week {@code YYYY-Www} 형식이 확인된 끝 주. null 이면 현재 주 − 1
     * @throws AdvisoryException 끝 주를 조회할 수 없으면 {@code WEEK_NOT_QUERYABLE}(400)
     */
    public PublicationHistoryListInfo getPublicationHistory(String week) {
        ReportWeek toWeek = resolveWeek(week);
        ReportWeek fromWeek = toWeek.minusWeeks(WEEKS - 1);
        // 오래된 주부터 — fromWeek ~ toWeek
        List<ReportWeek> weeks = IntStream.range(0, WEEKS).mapToObj(offset -> toWeek.minusWeeks(WEEKS - 1 - offset)).toList();

        List<Advisory> advisories = advisoryRepositoryPort.findAllPublicByIsoWeekIn(weeks).stream().sorted(LIST_ORDER).toList();
        Map<Long, Long> correctedBy = correctedBy(advisories);
        Map<Long, List<AdvisoryHistoryInfo>> histories = advisoryHistoryRepositoryPort.findAllByAdvisoryIdIn(advisories.stream().map(Advisory::id).toList())
            .stream()
            .map(AdvisoryHistoryInfo::from)
            .collect(Collectors.groupingBy(AdvisoryHistoryInfo::advisoryId));
        Map<String, AdvisoryDistrictQueryResult> districts = advisoryDistrictQueryPort.findAllByCodeIn(
                advisories.stream().map(Advisory::districtCode).distinct().toList()).stream()
            .collect(Collectors.toMap(AdvisoryDistrictQueryResult::code, Function.identity()));

        List<Entry> entries = advisories.stream()
            .map(advisory -> toEntry(advisory, districts.get(advisory.districtCode()), correctedBy.get(advisory.id()),
                histories.getOrDefault(advisory.id(), List.of())))
            .toList();
        return new PublicationHistoryListInfo(fromWeek, toWeek, entries);
    }

    /**
     * 생략하면 현재 주 − 1 이다(검토 후보와 같다 — 마감될 수 있는 가장 최근 주). 끝 주의 조회 창은 현재 주(KST, {@code Clock} 빈) ~
     * {@value AdvisoryPublicQueryProcessor#QUERYABLE_PAST_WEEKS}주 전으로 공개 동네 안내 · 검토 후보와 같다. 범위의 첫 주(끝 주 − 3)는 창보다 오래될 수 있고 그대로
     * 읽는다 — 안내는 지우지 않는다.
     */
    ReportWeek resolveWeek(String week) {
        ReportWeek current = ReportWeek.of(clock.instant());
        return week == null ? current.previous() : AdvisoryPublicQueryProcessor.resolveQueryableWeek(week, current);
    }

    /**
     * 이전 안내 ID → 그 안내를 정정한 새 안내 ID. 목록이 최근 것부터라 같은 안내를 정정했다는 안내가 둘이면(규칙 위반 데이터 — 정정 대상은 발행 중인 안내 하나뿐)
     * 가장 최근 것을 고르고 WARN 을 남긴다.
     */
    private static Map<Long, Long> correctedBy(List<Advisory> advisories) {
        Map<Long, Long> correctedBy = new HashMap<>();
        advisories.stream()
            .filter(advisory -> advisory.correctsAdvisoryId() != null)
            .forEach(advisory -> {
                Long existing = correctedBy.putIfAbsent(advisory.correctsAdvisoryId(), advisory.id());
                if (existing != null) {
                    log.warn("advisory corrected twice advisoryId={} correctedBy={},{}", advisory.correctsAdvisoryId(), existing, advisory.id());
                }
            });
        return correctedBy;
    }

    private static Entry toEntry(Advisory advisory, AdvisoryDistrictQueryResult district, Long correctedByAdvisoryId, List<AdvisoryHistoryInfo> histories) {
        if (district == null) {
            // district 행은 지우지 않으므로(폐지는 valid_to_year) 안내의 코드는 늘 있어야 한다 — 적재 사고다. 이름 없이 보이고 남긴다(검토 후보와 같다).
            log.warn("publication history district missing districtCode={}", advisory.districtCode());
        }
        return new Entry(AdvisoryInfo.from(advisory), district == null ? null : district.name(), district == null ? null : district.sigungu(),
            correctedByAdvisoryId, histories);
    }
}
