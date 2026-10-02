package com.sneezecast.domainlayer.member.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<MemberEntity, Long> {

    boolean existsByEmail(String email);

    Optional<MemberEntity> findByEmail(String email);
}
