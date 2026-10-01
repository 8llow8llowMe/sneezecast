package com.sneezecast.domainlayer.member.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberConsentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberConsentRepository extends JpaRepository<MemberConsentEntity, Long> {

}
