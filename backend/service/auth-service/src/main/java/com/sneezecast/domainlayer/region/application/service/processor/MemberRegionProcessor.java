package com.sneezecast.domainlayer.region.application.service.processor;

import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.info.MemberRegionInfo;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.domainlayer.region.application.port.out.MemberRegionRepositoryPort;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.domainlayer.region.domain.model.MemberRegion;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 동네의 규칙 — 무엇을 저장할 수 있는지(현행 행정동만), 회원당 1행을 어떻게 유지하는지(upsert), 저장된 코드를 어떻게 보여 주는지(폐지 여부는
 * 그때 다시 본다).
 *
 * <p><b>트랜잭션은 메서드마다 따로 건다.</b> surveillance 조회(원격)를 부르는 메서드({@link #requireSelectableDistrict},
 * {@link #describe})에는 걸지 않고, DB 구간({@link #save}, {@link #findByMemberId})에만 건다 — 호출자({@code RegionWebFacade})가 원격 조회를
 * 먼저 끝낸 뒤 DB 구간을 부른다. 커넥션을 쥔 채 원격 응답을 기다리지 않게 하려는 것이다 (architecture-guide §3-1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberRegionProcessor {

    private final MemberRegionRepositoryPort memberRegionRepositoryPort;
    private final DistrictQueryPort districtQueryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 동네로 저장할 수 있는 행정동인지 surveillance 에 확인한다. <b>트랜잭션 밖에서 부른다</b> (원격 호출).
     *
     * @return 현행 행정동 — 저장 뒤 응답에 이름을 싣는 데 쓴다
     * @throws RegionException 없는 코드 {@code REGION_001} · 폐지된 코드 {@code REGION_002}(400), 확인하지 못함 {@code REGION_004}(503) — 어느
     *                         경우든 호출자는 저장하지 않는다
     */
    public DistrictQueryResult requireSelectableDistrict(String districtCode) {
        DistrictQueryResult district = districtQueryPort.findByCode(districtCode)
            .orElseThrow(() -> new RegionException(RegionErrorCode.DISTRICT_NOT_FOUND));
        if (!district.active()) {
            throw new RegionException(RegionErrorCode.DISTRICT_ABOLISHED);
        }
        return district;
    }

    /**
     * 회원당 1행 upsert. 있으면 조회한 엔티티의 코드만 바꾸고(변경 감지, 같은 코드면 UPDATE 도 없다), 없으면 Snowflake ID 로 새 행을 넣는다.
     * 새로 매핑한 엔티티를 save 해서 기존 행을 덮지 않는다 (coding-conventions §8-1 Persistable 규칙).
     *
     * <p>{@link #requireSelectableDistrict} 로 검증을 마친 코드만 넘긴다. 조회와 삽입 사이에 같은 회원의 다른 요청이 먼저 넣으면 unique 위반이
     * {@code REGION_003}(409)이 된다 — 다시 보내면 갱신으로 풀린다.
     */
    @Transactional
    public MemberRegion save(long memberId, String districtCode) {
        Optional<MemberRegion> existing = memberRegionRepositoryPort.findByMemberId(memberId);
        if (existing.isPresent()) {
            return memberRegionRepositoryPort.changeDistrictCode(existing.get().id(), districtCode);
        }
        return memberRegionRepositoryPort.insert(MemberRegion.builder()
            .id(snowflakeIdGenerator.generateId())
            .memberId(memberId)
            .districtCode(districtCode)
            .build());
    }

    @Transactional(readOnly = true)
    public Optional<MemberRegion> findByMemberId(long memberId) {
        return memberRegionRepositoryPort.findByMemberId(memberId);
    }

    /**
     * 저장된 코드를 지금 기준으로 보여 준다. <b>트랜잭션 밖에서 부른다</b> (원격 호출). 저장된 행은 바꾸지 않는다 — 폐지돼도 자동 이관하지 않고
     * 화면이 다시 고르게 한다 (entity-design §1-4).
     *
     * <p>surveillance 에 코드가 없으면(폐지 코드도 지우지 않으므로 정상이라면 없다) 폐지로 보고 이름 없이 돌려준다. 저장 당시엔 있던 코드라
     * 데이터 이상이므로 WARN 을 남긴다 — 회원 식별정보 없이 코드만.
     *
     * @throws RegionException {@code REGION_004}(503) — surveillance 가 응답하지 못했다
     */
    public MemberRegionInfo describe(String districtCode) {
        Optional<DistrictQueryResult> district = districtQueryPort.findByCode(districtCode);
        if (district.isEmpty()) {
            log.warn("Saved member region district not found in surveillance districtCode={}", districtCode);
            return MemberRegionInfo.builder().code(districtCode).abolished(true).build();
        }
        return toInfo(district.get());
    }

    public MemberRegionInfo toInfo(DistrictQueryResult district) {
        return MemberRegionInfo.builder()
            .code(district.code())
            .name(district.name())
            .sigungu(district.sigungu())
            .abolished(!district.active())
            .build();
    }
}
