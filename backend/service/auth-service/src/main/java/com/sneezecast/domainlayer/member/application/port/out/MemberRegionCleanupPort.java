package com.sneezecast.domainlayer.member.application.port.out;

/**
 * 탈퇴 때 {@code region} 컨텍스트가 가진 회원 데이터를 정리하는 계약. member 의 application 계층이 region 을 import 하지 않도록 경계를 둔다(구현은
 * {@code member/adapter/out/region} 의 어댑터 하나).
 *
 * <p>내 동네({@code member_region})는 지우지 않는다 — 동의 이력과 함께 회원 파기(#244) 때 지운다(entity-design §8).
 */
public interface MemberRegionCleanupPort {

    /**
     * 회원의 관심 동네를 모두 지운다. 없어도 실패하지 않는다(멱등). 회원 탈퇴 갱신과 한 트랜잭션이어야 해서 <b>호출자 트랜잭션이 있어야 한다</b> — 없으면
     * 바로 실패한다.
     *
     * @return 지운 행 수
     */
    int deleteInterestRegions(long memberId);
}
