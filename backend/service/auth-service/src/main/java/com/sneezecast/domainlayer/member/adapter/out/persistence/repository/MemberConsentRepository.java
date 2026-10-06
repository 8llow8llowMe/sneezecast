package com.sneezecast.domainlayer.member.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberConsentEntity;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MemberConsentRepository extends JpaRepository<MemberConsentEntity, Long> {

    // idx_member_consent_member_id_type 의 선두 컬럼(member_id)을 탄다.
    List<MemberConsentEntity> findAllByMemberId(Long memberId);

    // idx_member_consent_member_id_type 를 탄다. 행이 없어도 그 범위를 잠근다(InnoDB next-key lock).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<MemberConsentEntity> findAllForUpdateByMemberIdAndType(Long memberId, ConsentType type);
}
