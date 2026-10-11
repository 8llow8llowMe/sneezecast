package com.sneezecast.domainlayer.aggregate.adapter.out.report;

import com.sneezecast.domainlayer.aggregate.application.port.out.AggregateReportQueryPort;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCounts;
import com.sneezecast.domainlayer.report.application.port.out.WeeklyReportRepositoryPort;
import com.sneezecast.domainlayer.report.application.port.out.query.WeeklyReportCountQueryResult;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 서비스의 {@code report} 컨텍스트에서 원시 보고 수를 읽는다. report 의 저장 포트만 쓰고 엔티티 · 리포지토리는 import 하지 않는다
 * ({@code ReportDistrictQueryAdapter} 와 같은 관계). 호출자(Processor) 트랜잭션 안에서 같은 DB 를 읽으므로 외부 I/O 가 아니다.
 *
 * <p>report 는 행정동 × 증상군 조합으로 센 줄을 주고, 여기서 행정동별 수치로 접는다. 둘 다인 보고는 호흡기 · 장관 양쪽에 센다.
 */
@Component
@RequiredArgsConstructor
public class AggregateReportQueryAdapter implements AggregateReportQueryPort {

    private final WeeklyReportRepositoryPort weeklyReportRepositoryPort;

    @Override
    public Map<String, AggregateCounts> countByDistrict(ReportWeek isoWeek) {
        Map<String, Tally> tallies = new HashMap<>();
        for (WeeklyReportCountQueryResult row : weeklyReportRepositoryPort.countByDistrictAndSymptoms(isoWeek)) {
            tallies.computeIfAbsent(row.districtCode(), code -> new Tally()).add(row);
        }
        Map<String, AggregateCounts> counts = new HashMap<>();
        tallies.forEach((districtCode, tally) -> counts.put(districtCode, tally.toCounts()));
        return counts;
    }

    @Override
    public List<ReportWeek> findReportedWeeks(ReportWeek fromInclusive, ReportWeek toExclusive) {
        return weeklyReportRepositoryPort.findReportedWeeks(fromInclusive, toExclusive);
    }

    private static final class Tally {

        private int participant;
        private int symptomatic;
        private int respiratory;
        private int enteric;
        private int revised;

        void add(WeeklyReportCountQueryResult row) {
            participant = Math.addExact(participant, row.reportCount());
            revised = Math.addExact(revised, row.revisedReportCount());
            if (row.symptoms().isEmpty()) {
                return;
            }
            symptomatic = Math.addExact(symptomatic, row.reportCount());
            if (row.symptoms().contains(SymptomGroup.RESPIRATORY)) {
                respiratory = Math.addExact(respiratory, row.reportCount());
            }
            if (row.symptoms().contains(SymptomGroup.ENTERIC)) {
                enteric = Math.addExact(enteric, row.reportCount());
            }
        }

        AggregateCounts toCounts() {
            return new AggregateCounts(participant, symptomatic, respiratory, enteric, revised);
        }
    }
}
