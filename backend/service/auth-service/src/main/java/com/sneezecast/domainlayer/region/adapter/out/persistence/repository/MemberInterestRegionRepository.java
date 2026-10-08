package com.sneezecast.domainlayer.region.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberInterestRegionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MemberInterestRegionRepository extends JpaRepository<MemberInterestRegionEntity, Long> {

    // uk_member_interest_region_member_id_district_code 의 앞 컬럼(member_id)을 탄다. id(Snowflake) 오름차순이 고른 순서다.
    List<MemberInterestRegionEntity> findByMemberIdOrderByIdAsc(Long memberId);

    /**
     * 벌크 DELETE 로 지운다. 파생 {@code deleteBy...} 는 조회한 엔티티를 하나씩 remove 해서, 같은 동네를 지우는 요청 두 건이 겹치면 늦은 쪽이
     * "지울 행 0건" 으로 실패(StaleState)한다 — 여기서는 0건도 정상(멱등)이다.
     *
     * @return 지운 행 수 (0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MemberInterestRegionEntity r where r.memberId = :memberId and r.districtCode = :districtCode")
    int deleteByMemberIdAndDistrictCode(Long memberId, String districtCode);
}
