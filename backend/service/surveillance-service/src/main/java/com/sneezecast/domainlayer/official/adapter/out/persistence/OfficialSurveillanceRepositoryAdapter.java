package com.sneezecast.domainlayer.official.adapter.out.persistence;

import com.sneezecast.domainlayer.official.adapter.out.persistence.repository.OfficialSurveillanceRepository;
import com.sneezecast.domainlayer.official.application.mapper.OfficialSurveillanceMapper;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSurveillanceRepositoryPort;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.model.OfficialObservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OfficialSurveillanceRepositoryAdapter implements OfficialSurveillanceRepositoryPort {

    private final OfficialSurveillanceRepository officialSurveillanceRepository;
    private final OfficialSurveillanceMapper officialSurveillanceMapper;

    @Override
    public List<OfficialObservation> findLatestWeekly(Map<OfficialProgram, LocalDate> periodStartLimits) {
        return officialSurveillanceRepository.findLatestWeekly(periodStartLimits).stream()
            .map(officialSurveillanceMapper::toDomainFromEntity)
            .toList();
    }
}
