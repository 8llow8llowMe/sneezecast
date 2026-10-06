package com.sneezecast.domainlayer.member.adapter.out.persistence;

import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.ReportPurgeRequestRepository;
import com.sneezecast.domainlayer.member.application.mapper.ReportPurgeRequestMapper;
import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeRequestRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportPurgeRequestRepositoryAdapter implements ReportPurgeRequestRepositoryPort {

    private final ReportPurgeRequestRepository reportPurgeRequestRepository;
    private final ReportPurgeRequestMapper reportPurgeRequestMapper;

    @Override
    public void save(ReportPurgeRequest request) {
        reportPurgeRequestRepository.save(reportPurgeRequestMapper.toEntityFromDomain(request));
    }

    @Override
    public boolean existsIncompleteByMemberId(long memberId) {
        return reportPurgeRequestRepository.existsByMemberIdAndCompletedAtIsNull(memberId);
    }
}
