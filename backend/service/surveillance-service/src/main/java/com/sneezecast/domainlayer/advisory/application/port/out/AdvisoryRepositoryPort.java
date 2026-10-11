package com.sneezecast.domainlayer.advisory.application.port.out;

import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 예방 안내 저장 포트 (entity-design §2-3).
 *
 * <p><b>동시 처리</b>: 전이 · 수정은 먼저 {@link #lockDistrictWeekOf} 로 같은 동네 · 주의 안내를 모두 잠그고(비관적 락) 최신 값을 읽는다 — 같은
 * 동네 · 주의 처리가 줄을 서므로 "발행 중인 안내는 하나" 를 경쟁 없이 지키고, 모든 전이가 같은 순서로 잠가 서로 교착하지 않는다. {@code version}
 * (@Version)은 그 위의 안전망이다 — {@link #update} 가 그 자리에서 flush 해 충돌을 {@code ADVISORY_006}(409, {@code AdvisoryException})으로 바꾼다.
 * 요청이 실은 버전과의 비교(화면이 읽은 뒤 다른 요청이 커밋한 경우)는 호출자가 잠근 뒤에 한다.
 */
public interface AdvisoryRepositoryPort {

    /** 잠그지 않는 조회 (운영자 상세). */
    Optional<Advisory> findById(long id);

    /**
     * 공개 동네 안내(#215)가 읽는 동네 · 주의 안내 — <b>발행됨(PUBLISHED) · 철회됨(RETRACTED)만</b> 준다. 초안 · 수정 · 승인 단계의 안내는 이 조회로 저장소
     * 밖에 나오지 않는다. 잠그지 않는다(공개 조회는 전이와 줄을 서지 않는다).
     */
    List<Advisory> findAllPublicByDistrictCodeAndIsoWeek(String districtCode, ReportWeek isoWeek);

    /**
     * 공개 집계(홈 · 지도)의 발행 안내 요약이 읽는 여러 동네 · 여러 주의 발행 중(PUBLISHED) 안내. 동네 수만큼 단건으로 읽지 않고 한 번에 읽는다
     * (coding-conventions §8-5). {@code districtCodes} 나 {@code isoWeeks} 가 비면 빈 목록이다. 잠그지 않는다.
     */
    List<Advisory> findAllPublishedByDistrictCodeInAndIsoWeekIn(Collection<String> districtCodes, Collection<ReportWeek> isoWeeks);

    /**
     * {@code advisoryId} 안내와 같은 동네 · 주의 모든 안내(자신 포함)를 잠금 읽기로 가져온다. 잠금은 트랜잭션 끝까지 간다. 그 안내가 없으면 빈 목록이다.
     *
     * <p><b>호출자 트랜잭션이 있어야 한다.</b> 같은 트랜잭션에서 그 안내를 이미 잠그지 않고 읽었다면 영속성 컨텍스트의 값(옛 값일 수 있다)이 돌아온다 — 그때도
     * {@link #update} 의 버전 조건이 충돌을 잡는다. 잠금을 기다리다 시간이 지나면 {@code ADVISORY_006} 이다.
     */
    List<Advisory> lockDistrictWeekOf(long advisoryId);

    /**
     * 새 안내를 저장한다. {@code id} 는 호출자가 Snowflake 로 정한다. INSERT 를 바로 내보낸다(flush).
     *
     * @return 감사 시각 · 버전(0)이 채워진 저장 결과
     */
    Advisory insert(Advisory advisory);

    /**
     * 있는 안내의 상태 · 제목 · 본문 · 발행 · 철회 시각을 {@code advisory} 값으로 고친다. 근거 집계 · 인용 수치 · 동네 · 주는 고치지 않는다(고정).
     * {@code advisory.version()} 은 이 트랜잭션에서 읽은 버전이어야 한다.
     *
     * <p><b>호출자 트랜잭션이 있어야 한다</b> (없으면 {@code IllegalTransactionStateException}).
     *
     * @return 올라간 버전 · 수정 시각이 채워진 저장 결과
     * @throws com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException 행이 없으면 {@code ADVISORY_001}, 버전이 다르거나
     *                                                                                     다른 트랜잭션이 먼저 고쳤으면 {@code ADVISORY_006}
     */
    Advisory update(Advisory advisory);
}
