package com.sneezecast.domainlayer.aggregate.application.port.out;

import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCalculation;
import com.sneezecast.domainlayer.aggregate.domain.model.DistrictWeeklyAggregate;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 행정동 × 주 집계 저장 포트 (entity-design §2-2). 재계산은 "없으면 insert, 있으면 update" 다 — 네이티브 upsert 는 컨벤션상 쓰지 않는다
 * (coding-conventions §8-4). 재계산 · 마감 스케줄러(#211)가 쓴다.
 *
 * <p><b>같은 칸 insert 경합</b>: 집계 · 마감 스케줄러는 한 번에 하나만 돈다(1단계 인스턴스 1개). 그래도 같은 {@code (district_code, iso_week)}
 * 를 두 번 insert 하면 {@code uk_district_weekly_aggregate_district_code_iso_week} 위반이 {@link #insert} 에서 그대로
 * {@code DataIntegrityViolationException} 으로 나간다. 보고 제출(#142)과 달리 도메인 예외로 바꾸지 않는다 — 그 트랜잭션은 rollback-only 라
 * 이어 갈 수 없고, 재계산이 멱등이라 다음 실행이 행을 보고 update 경로를 타면 저절로 맞춰진다. PK 충돌도 같은 방식으로 다음 실행에서 새 ID 로
 * 풀리므로 둘을 가를 이유가 없다.
 */
public interface DistrictWeeklyAggregateRepositoryPort {

    /** 주 하나의 모든 행정동 행. 순서는 정하지 않는다. */
    List<DistrictWeeklyAggregate> findAllByIsoWeek(ReportWeek isoWeek);

    /** 여러 주의 모든 행정동 행 — 기준선 · 전주 판정용으로 한 번에 읽는다. 빈 목록이면 조회하지 않고 빈 결과다. */
    List<DistrictWeeklyAggregate> findAllByIsoWeekIn(Collection<ReportWeek> isoWeeks);

    /**
     * 새 행을 저장한다. {@code id} 는 호출자가 Snowflake 로 정하고, 마감 전 행이어야 한다 ({@link DistrictWeeklyAggregate#newAggregate}).
     * INSERT 를 바로 내보내(flush) 제약 위반을 이 호출에서 드러낸다.
     *
     * @return 감사 시각이 채워진 저장 결과
     */
    DistrictWeeklyAggregate insert(DistrictWeeklyAggregate aggregate);

    /**
     * 있는 행의 수치 · 판정 · 규칙 버전 · 계산 시각을 다시 쓴다. <b>마감된 행은 고치지 않고 0 을 돌려준다.</b> 행이 없어도 0 이다 — 호출자가
     * 이번 주 행 목록으로 insert / update 를 가른다.
     *
     * <p><b>호출자 트랜잭션이 있어야 한다</b> (없으면 {@code IllegalTransactionStateException}). 갱신 뒤 영속성 컨텍스트를 비운다.
     *
     * @return 고친 행 수 (0 또는 1)
     */
    int updateCalculation(AggregateCalculation calculation);

    /**
     * 주 하나의 마감 안 된 행을 모두 마감한다. 이미 마감된 행은 그대로라 다시 불러도 된다. <b>호출자 트랜잭션이 있어야 한다.</b>
     *
     * @param finalizedAt 마감 시각 ({@code Clock} 빈 기준 지역 시각)
     * @return 이번에 마감한 행 수
     */
    int finalizeWeek(ReportWeek isoWeek, LocalDateTime finalizedAt);

    /**
     * {@code current} 보다 앞선 주 중 마감 안 된 행이 남은 주를 오래된 순으로. 마감 잡이 놓친 주를 따라잡는 데 쓴다. <b>집계 행이 하나도 없는
     * 지난 주는 나오지 않는다</b> — 그런 주는 원시 보고 쪽에서 찾아야 한다 (entity-design §2-2).
     *
     * @param current 현재 주 (이 주는 넣지 않는다)
     */
    List<ReportWeek> findUnfinalizedWeeksBefore(ReportWeek current);
}
