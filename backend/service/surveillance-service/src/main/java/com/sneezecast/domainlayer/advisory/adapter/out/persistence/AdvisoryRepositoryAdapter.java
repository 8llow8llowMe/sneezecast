package com.sneezecast.domainlayer.advisory.adapter.out.persistence;

import com.sneezecast.domainlayer.advisory.adapter.out.persistence.entity.AdvisoryEntity;
import com.sneezecast.domainlayer.advisory.adapter.out.persistence.repository.AdvisoryRepository;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.mapper.AdvisoryMapper;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AdvisoryRepositoryAdapter implements AdvisoryRepositoryPort {

    /** 공개 조회가 저장소 밖으로 내보내는 상태. 초안 · AI 초안 · 수정 · 승인은 여기에 없다. */
    private static final Set<AdvisoryStatus> PUBLIC_STATUSES = EnumSet.of(AdvisoryStatus.PUBLISHED, AdvisoryStatus.RETRACTED);

    private final AdvisoryRepository advisoryRepository;
    private final AdvisoryMapper advisoryMapper;

    @Override
    public Optional<Advisory> findById(long id) {
        return advisoryRepository.findById(id).map(advisoryMapper::toDomainFromEntity);
    }

    @Override
    public List<Advisory> findAllPublicByDistrictCodeAndIsoWeek(String districtCode, ReportWeek isoWeek) {
        return advisoryRepository.findAllByDistrictCodeAndIsoWeekAndStatusIn(districtCode, isoWeek.value(), PUBLIC_STATUSES).stream()
            .map(advisoryMapper::toDomainFromEntity)
            .toList();
    }

    @Override
    public List<Advisory> findAllPublishedByDistrictCodeInAndIsoWeekIn(Collection<String> districtCodes, Collection<ReportWeek> isoWeeks) {
        if (districtCodes.isEmpty() || isoWeeks.isEmpty()) {
            return List.of();
        }
        List<String> weeks = isoWeeks.stream().map(ReportWeek::value).toList();
        return advisoryRepository.findAllByDistrictCodeInAndIsoWeekInAndStatus(districtCodes, weeks, AdvisoryStatus.PUBLISHED).stream()
            .map(advisoryMapper::toDomainFromEntity)
            .toList();
    }

    @Override
    public List<Advisory> findAllByDistrictCodeInAndIsoWeek(Collection<String> districtCodes, ReportWeek isoWeek) {
        if (districtCodes.isEmpty()) {
            return List.of();
        }
        return advisoryRepository.findAllByDistrictCodeInAndIsoWeek(districtCodes, isoWeek.value()).stream()
            .map(advisoryMapper::toDomainFromEntity)
            .toList();
    }

    /**
     * 동네 · 주는 스칼라로 먼저 읽고(엔티티를 올리지 않는다), 그 동네 · 주를 {@code FOR UPDATE} 로 읽는다 — 잠금을 기다린 뒤의 최신 커밋 값이 엔티티로
     * 올라온다. 대상 행부터 PK 로 잠그지 않는 것은 모든 전이가 같은 인덱스 범위를 같은 순서로 잠가 서로 교착하지 않게 하려는 것이다.
     *
     * <p>잠금 대기 초과(MySQL {@code innodb_lock_wait_timeout}) · 교착 희생은 {@code ADVISORY_006} 으로 바꾼다 — 화면이 다시 읽고 다시 보낸다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Advisory> lockDistrictWeekOf(long advisoryId) {
        Optional<AdvisoryRepository.DistrictWeekView> districtWeek = advisoryRepository.findDistrictWeekById(advisoryId);
        if (districtWeek.isEmpty()) {
            return List.of();
        }
        try {
            return advisoryRepository.findAllByDistrictCodeAndIsoWeek(districtWeek.get().getDistrictCode(), districtWeek.get().getIsoWeek()).stream()
                .map(advisoryMapper::toDomainFromEntity)
                .toList();
        } catch (PessimisticLockingFailureException exception) {
            throw new AdvisoryException(AdvisoryErrorCode.CONCURRENT_MODIFICATION, exception);
        }
    }

    @Override
    public Advisory insert(Advisory advisory) {
        return advisoryMapper.toDomainFromEntity(advisoryRepository.saveAndFlush(advisoryMapper.toEntityFromDomain(advisory)));
    }

    /**
     * 조회한 엔티티를 바꿔 변경 감지로 고친다(coding-conventions §8-1). 같은 트랜잭션에서 이미 읽은 엔티티면 영속성 컨텍스트의 것을 그대로 쓴다.
     *
     * <p><b>낙관적 락 충돌을 여기서 도메인 오류로 바꾼다.</b> 버전이 다르면 바로 {@code ADVISORY_006} 이고, 같아도 다른 트랜잭션이 그 사이 커밋했으면
     * flush 의 {@code update ... where version = ?} 가 0건이 되어 {@link OptimisticLockingFailureException} 이 난다 — 이것도 같은 코드로 바꾼다.
     * 잠금 대기 초과 · 교착 희생({@code PessimisticLockingFailureException})도 같다. 그 트랜잭션은 rollback-only 라 다시 시도하지 않는다(화면이 다시 읽는다).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Advisory update(Advisory advisory) {
        AdvisoryEntity entity = advisoryRepository.findById(advisory.id())
            .orElseThrow(() -> new AdvisoryException(AdvisoryErrorCode.ADVISORY_NOT_FOUND));
        if (entity.getVersion().longValue() != advisory.version()) {
            throw new AdvisoryException(AdvisoryErrorCode.CONCURRENT_MODIFICATION);
        }
        entity.applyState(advisory.status(), advisory.title(), advisory.body(), advisory.publishedAt(), advisory.retractedAt());
        try {
            advisoryRepository.flush();
        } catch (ConcurrencyFailureException exception) {
            throw new AdvisoryException(AdvisoryErrorCode.CONCURRENT_MODIFICATION, exception);
        }
        return advisoryMapper.toDomainFromEntity(entity);
    }
}
