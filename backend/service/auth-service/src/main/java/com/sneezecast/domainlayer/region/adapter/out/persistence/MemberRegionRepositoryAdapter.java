package com.sneezecast.domainlayer.region.adapter.out.persistence;

import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberRegionEntity;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberRegionRepository;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.mapper.MemberRegionMapper;
import com.sneezecast.domainlayer.region.application.port.out.MemberRegionRepositoryPort;
import com.sneezecast.domainlayer.region.domain.model.MemberRegion;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MemberRegionRepositoryAdapter implements MemberRegionRepositoryPort {

    private final MemberRegionRepository memberRegionRepository;
    private final MemberRegionMapper memberRegionMapper;

    @Override
    public Optional<MemberRegion> findByMemberId(long memberId) {
        return memberRegionRepository.findByMemberId(memberId).map(memberRegionMapper::toDomainFromEntity);
    }

    /**
     * {@code saveAndFlush} 로 INSERT 를 바로 내보내 unique 위반이 이 자리에서 드러나게 한다. 커밋 시점까지 미루면 예외가 트랜잭션 프록시 밖에서
     * {@code DataIntegrityViolationException} 그대로 터져 500 이 된다.
     *
     * <p>{@code uk_member_region_member_id} 위반만 도메인 예외로 바꾼다. 다른 제약 위반(PK · NOT NULL 등)을 동시 저장으로 오인시키지 않는다.
     */
    @Override
    public MemberRegion insert(MemberRegion region) {
        try {
            MemberRegionEntity saved = memberRegionRepository.saveAndFlush(memberRegionMapper.toEntityFromDomain(region));
            return memberRegionMapper.toDomainFromEntity(saved);
        } catch (DataIntegrityViolationException exception) {
            if (isMemberIdUniqueViolation(exception)) {
                throw new RegionException(RegionErrorCode.REGION_SAVE_CONFLICT, exception);
            }
            throw exception;
        }
    }

    /**
     * 같은 트랜잭션의 {@link #findByMemberId} 가 이미 영속성 컨텍스트에 올려 둔 엔티티를 꺼내 바꾼다 — 추가 SELECT 없이 커밋 때 변경 감지로
     * UPDATE 가 나간다. 행이 없으면(같은 트랜잭션에서 조회했으니 정상이라면 없다) 상태가 어긋난 것이라 그대로 실패시킨다.
     *
     * <p>트랜잭션 밖에서 부르면 조회한 엔티티가 바로 분리돼 변경이 조용히 사라지므로 {@code MANDATORY} 로 막는다
     * ({@code MemberRepositoryAdapter} 의 변경 메서드와 같은 규칙).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MemberRegion changeDistrictCode(long regionId, String districtCode) {
        MemberRegionEntity entity = memberRegionRepository.findById(regionId)
            .orElseThrow(() -> new IllegalStateException("member_region row disappeared regionId=" + regionId));
        entity.changeDistrictCode(districtCode);
        return memberRegionMapper.toDomainFromEntity(entity);
    }

    /** MySQL 은 {@code for key 'member_region.uk_member_region_member_id'}, H2 는 {@code PUBLIC.UK_MEMBER_REGION_MEMBER_ID ...} 로 싣는다. */
    private boolean isMemberIdUniqueViolation(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(MemberRegionEntity.MEMBER_ID_UNIQUE_INDEX);
    }
}
