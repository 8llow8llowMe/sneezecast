package com.sneezecast.domainlayer.member.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberConsentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberConsentRepository extends JpaRepository<MemberConsentEntity, Long> {

    // idx_member_consent_member_id_type 의 선두 컬럼(member_id)을 탄다.
    List<MemberConsentEntity> findAllByMemberId(Long memberId);
}
