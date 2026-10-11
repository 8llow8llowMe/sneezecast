package com.sneezecast.domainlayer.official.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.official.adapter.out.persistence.entity.OfficialSurveillanceEntity;
import com.sneezecast.domainlayer.official.adapter.out.persistence.repository.custom.OfficialSurveillanceCustomRepository;
import org.springframework.data.repository.Repository;

/**
 * 읽기 전용 리포지토리. {@code JpaRepository} 가 아니라 {@link Repository} 를 상속해 {@code save} · {@code delete} 를 아예 열지 않는다 —
 * 감시 자료 행은 batch-service 가 JDBC upsert 로만 쓴다.
 */
public interface OfficialSurveillanceRepository extends Repository<OfficialSurveillanceEntity, Long>, OfficialSurveillanceCustomRepository {

}
