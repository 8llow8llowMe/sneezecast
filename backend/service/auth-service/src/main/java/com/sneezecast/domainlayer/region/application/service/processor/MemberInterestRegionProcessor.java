package com.sneezecast.domainlayer.region.application.service.processor;

import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.port.out.MemberInterestRegionRepositoryPort;
import com.sneezecast.domainlayer.region.application.port.out.MemberRegionRepositoryPort;
import com.sneezecast.domainlayer.region.domain.model.MemberInterestRegion;
import com.sneezecast.global.properties.RegionInterestProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관심 동네의 규칙 — 무엇을 더할 수 있는지(내 동네가 아니고 · 아직 고르지 않았고 · 상한 안), 상한을 어떻게 지키는지(칸 번호 slot + unique),
 * 동시 추가가 막혔을 때 무엇으로 알리는지.
 *
 * <p>DB 구간만 맡는다. 행정동 확인 · 이름 조회(원격)는 {@link MemberRegionProcessor} 를 그대로 쓰고, 호출자({@code InterestRegionWebFacade})가
 * 원격 구간과 이 클래스의 메서드 트랜잭션을 나눠 부른다 — 커넥션을 쥔 채 원격 응답을 기다리지 않게 한다 (architecture-guide §3-1).
 */
@Service
@RequiredArgsConstructor
public class MemberInterestRegionProcessor {

    private final MemberInterestRegionRepositoryPort memberInterestRegionRepositoryPort;
    private final MemberRegionRepositoryPort memberRegionRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final RegionInterestProperties regionInterestProperties;

    /** 고른 순서({@code id} 오름차순). 없으면 빈 목록. */
    @Transactional(readOnly = true)
    public List<MemberInterestRegion> findByMemberId(long memberId) {
        return memberInterestRegionRepositoryPort.findByMemberId(memberId);
    }

    /**
     * 관심 동네를 하나 더하고 더한 뒤의 목록을 돌려준다. {@link MemberRegionProcessor#requireSelectableDistrict} 로 현행 행정동임을 확인한 코드만
     * 넘긴다.
     *
     * <p>막는 순서: 내 동네와 같음 {@code REGION_007} → 이미 고름 {@code REGION_006} → 상한 {@code REGION_005}(모두 409). 상한은 "지금 개수 &gt;=
     * max-count" 로 본다 — 설정을 줄였을 때 칸 번호가 범위 밖인 옛 행이 남아 있어도 개수로 막는다. 통과하면 1..max-count 중 가장 작은 빈 칸에
     * 넣는다(개수가 상한보다 작으면 빈 칸이 반드시 있다).
     *
     * <p>조회와 삽입 사이에 같은 회원의 다른 추가가 먼저 들어가면 두 unique 중 하나에 막혀 {@code REGION_003} 으로 끝나고 이 트랜잭션은
     * 롤백된다 — 무엇과 겹쳤는지는 롤백 뒤 {@link #reclassifyAddConflict} 가 다시 읽어 가른다.
     */
    @Transactional
    public List<MemberInterestRegion> add(long memberId, String districtCode) {
        boolean sameAsMyRegion = memberRegionRepositoryPort.findByMemberId(memberId)
            .filter(myRegion -> myRegion.districtCode().equals(districtCode))
            .isPresent();
        if (sameAsMyRegion) {
            throw new RegionException(RegionErrorCode.INTEREST_REGION_SAME_AS_MY_REGION);
        }
        List<MemberInterestRegion> existing = memberInterestRegionRepositoryPort.findByMemberId(memberId);
        if (containsCode(existing, districtCode)) {
            throw new RegionException(RegionErrorCode.INTEREST_REGION_ALREADY_SELECTED);
        }
        int maxCount = regionInterestProperties.maxCount();
        if (existing.size() >= maxCount) {
            throw new RegionException(RegionErrorCode.INTEREST_REGION_LIMIT_EXCEEDED);
        }
        memberInterestRegionRepositoryPort.insert(MemberInterestRegion.builder()
            .id(snowflakeIdGenerator.generateId())
            .memberId(memberId)
            .districtCode(districtCode)
            .slot(firstFreeSlot(existing, maxCount))
            .build());
        return memberInterestRegionRepositoryPort.findByMemberId(memberId);
    }

    /**
     * {@link #add} 가 동시 추가로 막혔을 때({@code REGION_003}) 무엇과 겹쳤는지 가른다. <b>{@link #add} 의 트랜잭션이 롤백된 뒤 새 읽기 트랜잭션에서
     * 부른다</b> — unique 위반이 난 세션에서 다시 읽으면 실패한 INSERT 가 자동 flush 로 또 나간다.
     *
     * @param conflict {@link #add} 가 던진 {@code REGION_003}
     * @return 던질 예외. 그 코드가 지금 목록에 있으면(같은 동네를 동시에 더함) {@code REGION_006}, 없으면(다른 동네가 같은 칸을 먼저 차지함) 받은
     *         {@code REGION_003} 그대로 — 다시 보내면 다음 칸으로 들어가거나 상한 · 이미 고름으로 끝난다
     */
    @Transactional(readOnly = true)
    public RegionException reclassifyAddConflict(long memberId, String districtCode, RegionException conflict) {
        if (containsCode(memberInterestRegionRepositoryPort.findByMemberId(memberId), districtCode)) {
            return new RegionException(RegionErrorCode.INTEREST_REGION_ALREADY_SELECTED, conflict);
        }
        return conflict;
    }

    /** 관심 동네 하나를 빼고 뺀 뒤의 목록을 돌려준다. 목록에 없던 코드여도 성공이다(멱등 — 이미 지운 동네를 다시 지움). */
    @Transactional
    public List<MemberInterestRegion> remove(long memberId, String districtCode) {
        memberInterestRegionRepositoryPort.deleteByMemberIdAndDistrictCode(memberId, districtCode);
        return memberInterestRegionRepositoryPort.findByMemberId(memberId);
    }

    private static boolean containsCode(List<MemberInterestRegion> regions, String districtCode) {
        return regions.stream().anyMatch(region -> region.districtCode().equals(districtCode));
    }

    private static int firstFreeSlot(List<MemberInterestRegion> existing, int maxCount) {
        Set<Integer> used = existing.stream().map(MemberInterestRegion::slot).collect(Collectors.toSet());
        return IntStream.rangeClosed(1, maxCount)
            .filter(slot -> !used.contains(slot))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no free interest region slot below max-count count=" + existing.size()));
    }
}
