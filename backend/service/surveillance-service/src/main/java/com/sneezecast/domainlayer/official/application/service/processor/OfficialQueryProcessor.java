package com.sneezecast.domainlayer.official.application.service.processor;

import com.sneezecast.domainlayer.official.application.info.OfficialLatestInfo;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSurveillanceRepositoryPort;
import com.sneezecast.domainlayer.official.application.service.OfficialNotifiableProperties;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.OfficialObservation;
import com.sneezecast.domainlayer.official.domain.model.OfficialSourceLink;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 질병관리청 감시 자료의 최신 기준 주 (공개 공식 정보). <b>시민 자가보고(report · aggregate)를 읽지 않는다</b> — 두 정보를 한 응답 필드에 섞지 않는다.
 *
 * <ul>
 *   <li>프로그램 × 감염병(계열)마다 가장 최근 주다. 계열마다 기준 주가 다를 수 있다 (표본감시는 아직 {@code 집계 중} 인 칸을 적재하지 않는다).</li>
 *   <li>전수신고는 지금 주(질병관리청 주, 일요일 시작 — {@link Clock} 빈의 KST 날짜)에서
 *       {@code official.notifiable.display-lag-weeks} 주 전보다 최근 주를 보이지 않는다 ({@link OfficialNotifiableProperties}).</li>
 *   <li>적재가 실패 · 지연돼도 마지막으로 적재된 값을 그대로 주고, 그 값의 적재 시각({@code syncedAt})을 함께 준다 — 오래됐는지는 화면이 시각으로 판단한다.
 *       적재 주기(cron)는 설정이고 prod 스케줄은 기본 꺼짐이라, 서버가 "오래됨" 을 판정할 기준이 없다.</li>
 *   <li>발표 단계 · 쉬운 요약은 없다 — {@code official_surveillance} 에 출처가 없어 지어내지 않는다 (entity-design §3-2).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OfficialQueryProcessor {

    private static final Comparator<OfficialObservation> DISPLAY_ORDER = Comparator
        .comparing(OfficialObservation::program)
        .thenComparing(OfficialObservation::source)
        .thenComparing(OfficialObservation::diseaseKey)
        .thenComparing(OfficialObservation::metric)
        .thenComparing(OfficialObservation::regionLevel)
        .thenComparing(OfficialObservation::regionCode)
        .thenComparing(OfficialObservation::ageGroup);

    private final OfficialSurveillanceRepositoryPort officialSurveillanceRepositoryPort;
    private final OfficialNotifiableProperties officialNotifiableProperties;
    private final Clock clock;

    public OfficialLatestInfo getLatest() {
        List<OfficialObservation> rows = officialSurveillanceRepositoryPort.findLatestWeekly(Map.of(OfficialProgram.NOTIFIABLE, latestNotifiableWeekStart()));
        return group(rows);
    }

    /** 보일 수 있는 전수신고 주의 가장 늦은 시작일. 질병관리청 주는 일요일에 시작하므로 오늘이 든 주의 일요일에서 뒤로 간다 (data-api-analysis §5). */
    LocalDate latestNotifiableWeekStart() {
        LocalDate currentWeekStart = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        return currentWeekStart.minusWeeks(officialNotifiableProperties.displayLagWeeks());
    }

    private static OfficialLatestInfo group(List<OfficialObservation> rows) {
        Map<ProgramKey, Map<SeriesKey, List<OfficialObservation>>> grouped = new LinkedHashMap<>();
        rows.stream().sorted(DISPLAY_ORDER).forEach(row -> grouped
            .computeIfAbsent(ProgramKey.of(row), key -> new LinkedHashMap<>())
            .computeIfAbsent(SeriesKey.of(row), key -> new ArrayList<>())
            .add(row));

        List<OfficialLatestInfo.Program> programs = grouped.entrySet().stream()
            .map(program -> new OfficialLatestInfo.Program(
                program.getKey().program(),
                program.getKey().source(),
                OfficialSourceLink.of(program.getKey().program()).orElse(null),
                program.getValue().values().stream().map(OfficialQueryProcessor::toDisease).toList()))
            .toList();
        return new OfficialLatestInfo(programs);
    }

    /** 한 계열의 행은 같은 주다 (저장소가 계열마다 최신 기간 하나만 준다). 이름 · 분류 · 기간은 첫 행(연령대 순서상 첫 값)을 쓴다. */
    private static OfficialLatestInfo.Disease toDisease(List<OfficialObservation> rows) {
        OfficialObservation first = rows.getFirst();
        LocalDateTime syncedAt = rows.stream().map(OfficialObservation::syncedAt).max(Comparator.naturalOrder()).orElseThrow();
        return new OfficialLatestInfo.Disease(first.diseaseKey(), first.diseaseName(), first.diseaseGroup(), first.metric(), first.regionLevel(),
            first.regionCode(), first.regionName(), first.periodYear(), first.periodWeek(), first.periodStart(), first.periodEnd(), syncedAt,
            rows.stream().map(row -> new OfficialLatestInfo.Value(row.ageGroup(), row.metricValue())).toList());
    }

    private record ProgramKey(OfficialProgram program, OfficialSource source) {

        static ProgramKey of(OfficialObservation row) {
            return new ProgramKey(row.program(), row.source());
        }
    }

    private record SeriesKey(String diseaseKey, OfficialMetric metric, OfficialRegionLevel regionLevel, String regionCode) {

        static SeriesKey of(OfficialObservation row) {
            return new SeriesKey(row.diseaseKey(), row.metric(), row.regionLevel(), row.regionCode());
        }
    }
}
