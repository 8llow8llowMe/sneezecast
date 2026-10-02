package com.sneezecast.domainlayer.region.application.port.out;

import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.domain.model.MemberRegion;
import java.util.Optional;

public interface MemberRegionRepositoryPort {

    Optional<MemberRegion> findByMemberId(long memberId);

    /**
     * 새 행을 넣는다. 회원에게 이미 행이 있으면 {@code uk_member_region_member_id} 위반이다 — 앞선 조회에서 없던 행이 그 사이 생긴 것(같은
     * 회원의 동시 첫 저장)이라 재시도하면 갱신으로 풀린다.
     *
     * @throws RegionException {@code REGION_SAVE_CONFLICT}(409) — 회원당 1행 unique 위반
     */
    MemberRegion insert(MemberRegion region);

    /**
     * 기존 행의 행정동 코드를 바꾼다. 같은 트랜잭션에서 조회한 엔티티를 변경 감지로 고친다 — 새로 매핑한 엔티티를 저장하면 PK 위반이다
     * (coding-conventions §8-1). 호출자 트랜잭션 안에서 불러야 반영된다.
     *
     * @param regionId 바꿀 행의 아이디 ({@link #findByMemberId} 결과)
     */
    MemberRegion changeDistrictCode(long regionId, String districtCode);
}
