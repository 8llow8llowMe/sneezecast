package com.sneezecast.domainlayer.member.adapter.out.region;

import com.sneezecast.domainlayer.member.application.port.out.MemberRegionCleanupPort;
import com.sneezecast.domainlayer.region.application.port.out.MemberInterestRegionRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * member → region 컨텍스트 교차 의존을 이 어댑터 한 지점으로 한정한다 ({@code member/adapter/out/auth} 와 같은 방식). region 은 여전히 다른 컨텍스트를
 * import 하지 않는다.
 *
 * <p>트랜잭션 강제({@code MANDATORY})는 region 저장소 어댑터가 건다 — 탈퇴 트랜잭션 밖에서 부르면 바로 실패한다.
 */
@Component
@RequiredArgsConstructor
public class MemberRegionCleanupAdapter implements MemberRegionCleanupPort {

    private final MemberInterestRegionRepositoryPort memberInterestRegionRepositoryPort;

    @Override
    public int deleteInterestRegions(long memberId) {
        return memberInterestRegionRepositoryPort.deleteAllByMemberId(memberId);
    }
}
