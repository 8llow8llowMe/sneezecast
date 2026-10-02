package com.sneezecast.domainlayer.region.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberRegionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRegionRepository extends JpaRepository<MemberRegionEntity, Long> {

    // uk_member_region_member_id 를 탄다. 회원당 1행이라 Optional 이다.
    Optional<MemberRegionEntity> findByMemberId(Long memberId);
}
