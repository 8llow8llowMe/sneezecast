package com.sneezecast.domainlayer.region.application.port.out;

import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.domain.model.MemberInterestRegion;
import java.util.List;

public interface MemberInterestRegionRepositoryPort {

    /** 회원의 관심 동네를 고른 순서({@code id} 오름차순)로 돌려준다. 없으면 빈 목록. */
    List<MemberInterestRegion> findByMemberId(long memberId);

    /**
     * 새 행을 넣는다. 같은 회원에 같은 코드({@code uk_member_interest_region_member_id_district_code}) 또는 같은 칸
     * ({@code uk_member_interest_region_member_id_slot})이 이미 있으면 unique 위반이다 — 앞선 조회에서 없던 행이 그 사이 생긴 것(같은 회원의 동시
     * 추가)이다. 어느 제약에 막혔는지로 뜻을 정하지 않는다: 같은 코드를 같은 칸에 넣는 두 요청은 DB 가 어느 쪽 제약을 먼저 보고하는지 정해져
     * 있지 않다. 호출자가 다시 읽어 가른다.
     *
     * @throws RegionException {@code REGION_SAVE_CONFLICT}(409) — 두 unique 중 하나에 막혔다. 호출자 트랜잭션은 롤백된다
     */
    MemberInterestRegion insert(MemberInterestRegion region);

    /**
     * 회원의 관심 동네 하나를 지운다. 없어도 실패하지 않는다(멱등).
     *
     * @return 지운 행 수 (0 또는 1)
     */
    int deleteByMemberIdAndDistrictCode(long memberId, String districtCode);
}
