package com.sneezecast.domainlayer.region.adapter.out.persistence;

import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberInterestRegionEntity;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberInterestRegionRepository;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.mapper.MemberInterestRegionMapper;
import com.sneezecast.domainlayer.region.application.port.out.MemberInterestRegionRepositoryPort;
import com.sneezecast.domainlayer.region.domain.model.MemberInterestRegion;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberInterestRegionRepositoryAdapter implements MemberInterestRegionRepositoryPort {

    private final MemberInterestRegionRepository memberInterestRegionRepository;
    private final MemberInterestRegionMapper memberInterestRegionMapper;

    @Override
    public List<MemberInterestRegion> findByMemberId(long memberId) {
        return memberInterestRegionRepository.findByMemberIdOrderByIdAsc(memberId).stream()
            .map(memberInterestRegionMapper::toDomainFromEntity)
            .toList();
    }

    /**
     * {@code MemberRegionRepositoryAdapter#insert} 와 같은 규칙이다 — {@code saveAndFlush} 로 INSERT 를 바로 내보내 unique 위반이 이 자리에서
     * 드러나게 하고(커밋 때 터지면 트랜잭션 프록시 밖의 500), 이 테이블의 두 unique 위반만 도메인 예외로 바꾼다. 다른 제약 위반(PK · NOT NULL
     * 등)을 동시 추가로 오인시키지 않는다.
     */
    @Override
    public MemberInterestRegion insert(MemberInterestRegion region) {
        try {
            MemberInterestRegionEntity saved = memberInterestRegionRepository.saveAndFlush(memberInterestRegionMapper.toEntityFromDomain(region));
            return memberInterestRegionMapper.toDomainFromEntity(saved);
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueViolation(exception)) {
                throw new RegionException(RegionErrorCode.REGION_SAVE_CONFLICT, exception);
            }
            throw exception;
        }
    }

    @Override
    public int deleteByMemberIdAndDistrictCode(long memberId, String districtCode) {
        return memberInterestRegionRepository.deleteByMemberIdAndDistrictCode(memberId, districtCode);
    }

    /** MySQL 은 {@code for key 'member_interest_region.uk_...'}, H2 는 {@code PUBLIC.UK_..._INDEX_n ...} 로 제약 이름을 싣는다. */
    private boolean isUniqueViolation(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains(MemberInterestRegionEntity.MEMBER_DISTRICT_UNIQUE_INDEX) || lower.contains(MemberInterestRegionEntity.MEMBER_SLOT_UNIQUE_INDEX);
    }
}
