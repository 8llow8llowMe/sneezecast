package com.sneezecast.domainlayer.member.adapter.out.persistence;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.ReportPurgeRequestEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.ReportPurgeRequestRepository;
import com.sneezecast.domainlayer.member.application.mapper.ReportPurgeRequestMapper;
import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeRequestRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    public Optional<ReportPurgeRequest> findById(long id) {
        return reportPurgeRequestRepository.findById(id).map(reportPurgeRequestMapper::toDomainFromEntity);
    }

    @Override
    public List<ReportPurgeRequest> findDue(LocalDateTime secondCallDueAt, int limit) {
        return reportPurgeRequestRepository.findDue(secondCallDueAt, PageRequest.ofSize(limit)).stream()
            .map(reportPurgeRequestMapper::toDomainFromEntity)
            .toList();
    }

    /**
     * 벌크 갱신은 트랜잭션 밖에서 쿼리 단계에 가서야 실패한다. 기록 단위(갱신 + 재조회)가 한 트랜잭션에 묶여야 하므로 {@code MANDATORY} 로 호출자
     * 트랜잭션을 강제한다 (아래 둘도 같다).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int recordSuccess(long id, LocalDateTime succeededAt, LocalDateTime completedAt) {
        return reportPurgeRequestRepository.recordSuccess(id, succeededAt, completedAt);
    }

    /** 사유는 컬럼 길이(200)에서 자른다 — 길이 초과로 실패 기록 자체가 실패하면 시도 횟수 · 경보가 멈춘다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int recordFailure(long id, String lastError, LocalDateTime failedAt) {
        return reportPurgeRequestRepository.recordFailure(id, truncate(lastError), failedAt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteCompletedBefore(LocalDateTime threshold) {
        return reportPurgeRequestRepository.deleteCompletedBefore(threshold);
    }

    private static String truncate(String lastError) {
        if (lastError == null || lastError.length() <= ReportPurgeRequestEntity.LAST_ERROR_MAX_LENGTH) {
            return lastError;
        }
        return lastError.substring(0, ReportPurgeRequestEntity.LAST_ERROR_MAX_LENGTH);
    }
}
