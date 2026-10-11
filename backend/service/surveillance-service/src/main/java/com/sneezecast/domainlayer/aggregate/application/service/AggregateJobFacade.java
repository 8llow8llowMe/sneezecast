package com.sneezecast.domainlayer.aggregate.application.service;

import com.sneezecast.domainlayer.aggregate.application.model.AggregateWeekResult;
import com.sneezecast.domainlayer.aggregate.application.port.in.AggregateJobUseCase;
import com.sneezecast.domainlayer.aggregate.application.service.processor.AggregateCommandProcessor;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 집계 재계산 · 주 마감 오케스트레이션. <b>여기에는 트랜잭션을 걸지 않는다</b> — 주 하나가 트랜잭션 하나라서 Processor 메서드에 건다. 놓친 주
 * 여럿을 한 트랜잭션으로 묶으면 한 주의 실패가 이미 마감한 앞 주까지 되돌린다.
 *
 * <p>현재 시각은 {@link Clock} 빈(KST)에서 받는다. 주는 {@link ReportWeek#of(java.time.Instant)} 로 정한다 — 보고 주와 같은 정의다.
 *
 * <p>로그에는 주 · 건수만 남긴다 (익명 집계, 보고자 정보 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregateJobFacade implements AggregateJobUseCase {

    private final AggregateCommandProcessor aggregateCommandProcessor;
    private final AggregateJobProperties aggregateJobProperties;
    private final Clock clock;

    @Override
    public AggregateWeekResult recalculateCurrentWeek() {
        AggregateWeekResult result = aggregateCommandProcessor.recalculateInProgress(ReportWeek.of(clock.instant()));
        log.info("aggregate recalculated week={} districts={} inserted={} updated={} skippedFinalized={}", result.isoWeek(), result.reportedDistricts(),
            result.inserted(), result.updated(), result.alreadyFinalized());
        return result;
    }

    /**
     * 마감 유예({@code close-grace})를 뺀 시각이 속한 주보다 앞선 주를 마감한다. 월요일 00:09(유예 9분) 이후면 지난 주까지, 그 전이면 지지난 주까지다
     * — 일요일 밤에 시작한 보고가 커밋되기 전에 지난 주를 닫지 않게 한다.
     */
    @Override
    public List<AggregateWeekResult> closeOverdueWeeks() {
        ReportWeek cutoff = ReportWeek.of(clock.instant().minus(aggregateJobProperties.closeGrace()));
        List<AggregateWeekResult> results = new ArrayList<>();
        // 주마다 새 트랜잭션으로 오래된 주부터 닫는다 — 뒤 주의 판정이 앞 주를 마감된 기준선 · 전주로 봐야 한다. 실패하면 예외를 그대로 내보내
        // 뒤 주를 이번 실행에서 닫지 않는다(다음 실행이 앞 주부터 다시 고른다). 주 수만큼 Processor 를 부르는 것은 트랜잭션 경계를 주마다 두기 위해서다.
        for (ReportWeek week : aggregateCommandProcessor.findWeeksToClose(cutoff)) {
            AggregateWeekResult result = aggregateCommandProcessor.close(week);
            log.info("aggregate week closed week={} districts={} inserted={} updated={} finalized={} skippedFinalized={}", result.isoWeek(),
                result.reportedDistricts(), result.inserted(), result.updated(), result.finalized(), result.alreadyFinalized());
            results.add(result);
        }
        return results;
    }
}
