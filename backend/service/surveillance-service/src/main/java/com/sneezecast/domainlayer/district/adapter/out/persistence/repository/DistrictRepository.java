package com.sneezecast.domainlayer.district.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.district.adapter.out.persistence.entity.DistrictEntity;
import com.sneezecast.domainlayer.district.adapter.out.persistence.repository.custom.DistrictCustomRepository;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * 읽기 전용 리포지토리. {@code JpaRepository} 가 아니라 {@link Repository} 를 상속해 {@code save} · {@code delete} 를 아예 열지 않는다 —
 * 행정동 행은 batch-service 가 JDBC upsert 로만 쓴다.
 */
public interface DistrictRepository extends Repository<DistrictEntity, Long>, DistrictCustomRepository {

    /** {@code uk_district_code} 로 찾는다. 폐지 여부와 무관하다. */
    Optional<DistrictEntity> findByCode(String code);
}
