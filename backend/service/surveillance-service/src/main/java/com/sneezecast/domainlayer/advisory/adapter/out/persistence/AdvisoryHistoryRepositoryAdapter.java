package com.sneezecast.domainlayer.advisory.adapter.out.persistence;

import com.sneezecast.domainlayer.advisory.adapter.out.persistence.repository.AdvisoryHistoryRepository;
import com.sneezecast.domainlayer.advisory.application.mapper.AdvisoryHistoryMapper;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryHistoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryHistory;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdvisoryHistoryRepositoryAdapter implements AdvisoryHistoryRepositoryPort {

    private final AdvisoryHistoryRepository advisoryHistoryRepository;
    private final AdvisoryHistoryMapper advisoryHistoryMapper;

    @Override
    public AdvisoryHistory insert(AdvisoryHistory history) {
        return advisoryHistoryMapper.toDomainFromEntity(advisoryHistoryRepository.saveAndFlush(advisoryHistoryMapper.toEntityFromDomain(history)));
    }

    @Override
    public List<AdvisoryHistory> findAllByAdvisoryId(long advisoryId) {
        return advisoryHistoryRepository.findAllByAdvisoryIdOrderByCreatedAtAscIdAsc(advisoryId).stream().map(advisoryHistoryMapper::toDomainFromEntity).toList();
    }

    @Override
    public List<AdvisoryHistory> findAllByAdvisoryIdIn(Collection<Long> advisoryIds) {
        if (advisoryIds.isEmpty()) {
            return List.of();
        }
        return advisoryHistoryRepository.findAllByAdvisoryIdInOrderByCreatedAtAscIdAsc(advisoryIds).stream()
            .map(advisoryHistoryMapper::toDomainFromEntity)
            .toList();
    }
}
