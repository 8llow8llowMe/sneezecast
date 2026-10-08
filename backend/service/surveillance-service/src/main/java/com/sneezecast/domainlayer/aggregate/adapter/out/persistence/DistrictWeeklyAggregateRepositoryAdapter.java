package com.sneezecast.domainlayer.aggregate.adapter.out.persistence;

import com.sneezecast.domainlayer.aggregate.adapter.out.persistence.repository.DistrictWeeklyAggregateRepository;
import com.sneezecast.domainlayer.aggregate.application.mapper.DistrictWeeklyAggregateMapper;
import com.sneezecast.domainlayer.aggregate.application.port.out.DistrictWeeklyAggregateRepositoryPort;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCalculation;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCounts;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateJudgement;
import com.sneezecast.domainlayer.aggregate.domain.model.DistrictWeeklyAggregate;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DistrictWeeklyAggregateRepositoryAdapter implements DistrictWeeklyAggregateRepositoryPort {

    private final DistrictWeeklyAggregateRepository districtWeeklyAggregateRepository;
    private final DistrictWeeklyAggregateMapper districtWeeklyAggregateMapper;
    private final Clock clock;

    @Override
    public List<DistrictWeeklyAggregate> findAllByIsoWeek(ReportWeek isoWeek) {
        return districtWeeklyAggregateRepository.findAllByIsoWeek(isoWeek.value()).stream().map(districtWeeklyAggregateMapper::toDomainFromEntity).toList();
    }

    @Override
    public List<DistrictWeeklyAggregate> findAllByIsoWeekIn(Collection<ReportWeek> isoWeeks) {
        if (isoWeeks.isEmpty()) {
            return List.of();
        }
        List<String> values = isoWeeks.stream().map(ReportWeek::value).distinct().toList();
        return districtWeeklyAggregateRepository.findAllByIsoWeekIn(values).stream().map(districtWeeklyAggregateMapper::toDomainFromEntity).toList();
    }

    /** {@code saveAndFlush} 로 INSERT 를 바로 내보내 제약 위반이 이 자리에서 드러나게 한다. 위반은 바꾸지 않고 그대로 던진다 (포트 문서). */
    @Override
    public DistrictWeeklyAggregate insert(DistrictWeeklyAggregate aggregate) {
        if (aggregate.finalized()) {
            throw new IllegalArgumentException("마감된 행은 새로 저장하지 않습니다. district=" + aggregate.districtCode() + ", week=" + aggregate.isoWeek());
        }
        return districtWeeklyAggregateMapper.toDomainFromEntity(
            districtWeeklyAggregateRepository.saveAndFlush(districtWeeklyAggregateMapper.toEntityFromDomain(aggregate)));
    }

    /**
     * {@code updatedAt} 은 Auditing 과 같은 기준(JVM 기본 시간대의 지역 시각)으로 쓰되 순간은 {@link Clock} 빈에서 받는다
     * ({@code WeeklyReportRepositoryAdapter#updateCurrent} 와 같다).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int updateCalculation(AggregateCalculation calculation) {
        AggregateCounts counts = calculation.counts();
        AggregateJudgement judgement = calculation.judgement();
        return districtWeeklyAggregateRepository.updateCalculation(calculation.districtCode(), calculation.isoWeek().value(), counts.participantCount(),
            counts.symptomaticCount(), counts.respiratoryCount(), counts.entericCount(), counts.revisedReportCount(), judgement.level(),
            judgement.insufficientReason(), judgement.baselineParticipantCount(), judgement.baselineSymptomaticCount(), calculation.ruleVersion(),
            calculation.calculatedAt(), now());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int finalizeWeek(ReportWeek isoWeek, LocalDateTime finalizedAt) {
        return districtWeeklyAggregateRepository.finalizeWeek(isoWeek.value(), finalizedAt, now());
    }

    @Override
    public List<ReportWeek> findUnfinalizedWeeksBefore(ReportWeek current) {
        return districtWeeklyAggregateRepository.findUnfinalizedIsoWeeksBefore(current.value()).stream().map(ReportWeek::parse).toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }
}
