package com.sneezecast.domainlayer.member.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.ReportPurgeRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportPurgeRequestRepository extends JpaRepository<ReportPurgeRequestEntity, Long> {

    // idx_report_purge_request_member_id 를 탄다.
    boolean existsByMemberIdAndCompletedAtIsNull(Long memberId);
}
