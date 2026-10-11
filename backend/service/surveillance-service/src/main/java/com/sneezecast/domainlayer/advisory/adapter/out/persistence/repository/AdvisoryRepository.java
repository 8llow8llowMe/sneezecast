package com.sneezecast.domainlayer.advisory.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.advisory.adapter.out.persistence.entity.AdvisoryEntity;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AdvisoryRepository extends JpaRepository<AdvisoryEntity, Long> {

    /**
     * 안내의 동네 · 주만 읽는다(스칼라라 엔티티를 영속성 컨텍스트에 올리지 않는다). 두 값은 생성 뒤 바뀌지 않으므로(updatable = false) 잠그지 않은 읽기로
     * 충분하다 — 이어서 {@link #findAllByDistrictCodeAndIsoWeek} 가 잠금 읽기로 엔티티를 처음 올리게 한다.
     */
    @Query("select a.districtCode as districtCode, a.isoWeek as isoWeek from AdvisoryEntity a where a.id = :id")
    Optional<DistrictWeekView> findDistrictWeekById(Long id);

    /**
     * 동네 · 주의 모든 안내를 {@code SELECT … FOR UPDATE} 로 읽는다 — 잠금을 기다린 뒤 최신 커밋 값(current read)을 돌려준다. 같은 동네 · 주의 전이가
     * 이 잠금에서 줄을 선다. {@code idx_advisory_district_code_status_published_at} 앞 컬럼(district_code)을 타므로 InnoDB 는 그 동네의 다른 주 행까지
     * 잠근다(같은 동네 처리끼리 직렬화 — 1단계 운영자 수에서는 문제없다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<AdvisoryEntity> findAllByDistrictCodeAndIsoWeek(String districtCode, String isoWeek);

    /** 공개 조회 — 동네 · 주의 안내 중 {@code statuses} 인 것. 잠그지 않는다. */
    List<AdvisoryEntity> findAllByDistrictCodeAndIsoWeekAndStatusIn(String districtCode, String isoWeek, Collection<AdvisoryStatus> statuses);

    /** 공개 집계의 발행 안내 요약 — 여러 동네 · 여러 주의 안내 중 {@code status} 인 것. 잠그지 않는다. */
    List<AdvisoryEntity> findAllByDistrictCodeInAndIsoWeekInAndStatus(Collection<String> districtCodes, Collection<String> isoWeeks, AdvisoryStatus status);

    interface DistrictWeekView {

        String getDistrictCode();

        String getIsoWeek();
    }
}
