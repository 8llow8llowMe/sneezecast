package com.sneezecast.domainlayer.advisory.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.advisory.adapter.out.persistence.entity.AdvisoryHistoryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvisoryHistoryRepository extends JpaRepository<AdvisoryHistoryEntity, Long> {

    /**
     * {@code idx_advisory_history_advisory_id} 를 탄다. 같은 트랜잭션에서 이어 쌓인 행은 생성 시각이 같을 수 있어 Snowflake ID(시간 순)로 한 번 더
     * 가른다.
     */
    List<AdvisoryHistoryEntity> findAllByAdvisoryIdOrderByCreatedAtAscIdAsc(Long advisoryId);
}
