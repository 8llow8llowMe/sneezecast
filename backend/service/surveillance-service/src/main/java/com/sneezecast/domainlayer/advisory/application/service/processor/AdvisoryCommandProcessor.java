package com.sneezecast.domainlayer.advisory.application.service.processor;

import com.sneezecast.domainlayer.advisory.application.command.AdvisoryCorrectCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryDraftCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryEditCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryRetractCommand;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryInfo;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryAggregateQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryHistoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryAggregateQueryResult;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryHistory;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 예방 안내 초안 생성과 상태 전이 (entity-design §2-3 · §2-5). <b>모든 생성 · 전이 · 수정이 이력 한 행을 남긴다</b> — 같은 트랜잭션이라 안내와 이력이
 * 함께 커밋되거나 함께 롤백된다. 트랜잭션은 호출자(WebFacade)가 건다 — 외부 I/O 가 없고 재시도도 하지 않는다. 여러 단계를 한 요청에서 하려면(예: 승인하고
 * 발행) Facade 가 한 트랜잭션 안에서 이 메서드들을 이어 부르고, 앞 단계가 돌려준 {@code version} 을 다음 단계에 넘긴다.
 *
 * <p><b>확인 순서</b>: 같은 동네 · 주 잠금 → 안내 없음(404) → 요청 버전 불일치(409 {@code ADVISORY_006}) → 표에 없는 전이(409 {@code ADVISORY_005}).
 * 버전을 먼저 보는 것은 화면이 옛 상태를 보고 보낸 요청이면 "다시 읽어라" 가 맞는 답이기 때문이다.
 *
 * <p>자동 발행 경로는 없다 — 모든 메서드가 운영자 ID 를 받는다. 로그에는 안내 ID · 상태만 남기고 운영자 ID(회원 식별정보)는 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryCommandProcessor {

    private final AdvisoryRepositoryPort advisoryRepositoryPort;
    private final AdvisoryHistoryRepositoryPort advisoryHistoryRepositoryPort;
    private final AdvisoryAggregateQueryPort advisoryAggregateQueryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    /**
     * 동네 · 주의 집계로 초안을 만든다 → DRAFT. 인용 수치는 집계에서 복사해 고정한다.
     *
     * @throws AdvisoryException 집계 행 없음 {@code ADVISORY_002}(404), 마감 전 {@code ADVISORY_003} · 자료 부족 {@code ADVISORY_004}(409)
     */
    public AdvisoryInfo createDraft(long operatorId, AdvisoryDraftCommand command) {
        requireOperator(operatorId);
        AdvisoryAggregateQueryResult aggregate = advisoryAggregateQueryPort.findByDistrictCodeAndIsoWeek(command.districtCode(), command.isoWeek())
            .orElseThrow(() -> new AdvisoryException(AdvisoryErrorCode.AGGREGATE_NOT_FOUND, command.districtCode(), command.isoWeek().value()));
        // 충분한 자료가 있을 때에만 예방 정보를 전한다 — 마감 전 집계는 아직 바뀌고, 자료 부족 집계는 수치로 위험을 암시하면 안 된다.
        if (!aggregate.finalized()) {
            throw new AdvisoryException(AdvisoryErrorCode.AGGREGATE_NOT_FINALIZED, command.districtCode(), command.isoWeek().value());
        }
        if (aggregate.level() == AggregateLevel.INSUFFICIENT) {
            throw new AdvisoryException(AdvisoryErrorCode.AGGREGATE_INSUFFICIENT, command.districtCode(), command.isoWeek().value());
        }
        AdvisoryCitation citation = new AdvisoryCitation(aggregate.participantCount(), aggregate.symptomaticCount(), aggregate.respiratoryCount(),
            aggregate.entericCount(), aggregate.level(), aggregate.ruleVersion());
        Advisory saved = advisoryRepositoryPort.insert(Advisory.newDraft(snowflakeIdGenerator.generateId(), aggregate.aggregateId(), command.districtCode(),
            command.isoWeek(), citation, command.title(), command.body()));
        record(saved, null, operatorId, null);
        return AdvisoryInfo.from(saved);
    }

    /** 제목 · 본문을 고친다 → EDITED. 승인된 안내를 고치면 승인이 풀린다(다시 승인받아야 발행할 수 있다). */
    public AdvisoryInfo edit(long operatorId, long advisoryId, AdvisoryEditCommand command) {
        Advisory current = lockForTransition(operatorId, advisoryId, command.expectedVersion(), AdvisoryStatus.EDITED).target();
        return save(current, current.edit(command.title(), command.body()), operatorId, command.memo());
    }

    /** 현재 본문을 승인한다 → APPROVED. */
    public AdvisoryInfo approve(long operatorId, long advisoryId, long expectedVersion) {
        Advisory current = lockForTransition(operatorId, advisoryId, expectedVersion, AdvisoryStatus.APPROVED).target();
        return save(current, current.approve(), operatorId, null);
    }

    /**
     * 승인된 안내를 발행한다 → PUBLISHED. <b>같은 동네 · 주에 발행 중인 안내는 하나뿐이다</b> — 이미 있으면 409 {@code ADVISORY_007}(바꾸려면 그 안내를
     * 철회 · 정정한다). 동네 안내(#215)가 한 동네 · 주에 안내 하나를 보이게 하려는 규칙이다.
     *
     * <p>판단은 잠금 읽기(그 동네 · 주 전체 {@code FOR UPDATE})의 결과로 한다 — 같은 동네 · 주의 동시 발행은 잠금에서 줄을 서고, 뒤에 선 쪽은 앞 쪽이
     * 커밋한 PUBLISHED 를 보고 거부된다. 잠그지 않는 존재 확인(MySQL REPEATABLE READ 스냅샷)이면 둘 다 지날 수 있다.
     */
    public AdvisoryInfo publish(long operatorId, long advisoryId, long expectedVersion) {
        LockedDistrictWeek locked = lockForTransition(operatorId, advisoryId, expectedVersion, AdvisoryStatus.PUBLISHED);
        Advisory current = locked.target();
        if (locked.hasOtherPublished()) {
            throw new AdvisoryException(AdvisoryErrorCode.ALREADY_PUBLISHED, current.districtCode(), current.isoWeek().value());
        }
        return save(current, current.publish(now()), operatorId, null);
    }

    /** 발행 중인 안내를 철회한다 → RETRACTED(끝 상태). 사유는 이력 메모로 남긴다. */
    public AdvisoryInfo retract(long operatorId, long advisoryId, AdvisoryRetractCommand command) {
        Advisory current = lockForTransition(operatorId, advisoryId, command.expectedVersion(), AdvisoryStatus.RETRACTED).target();
        return save(current, current.retract(now()), operatorId, command.reason());
    }

    /**
     * 정정 발행(#267) — 발행 중인 안내를 철회하고 고친 제목 · 본문으로 새 안내를 발행해 {@code corrects_advisory_id} 로 잇는다. <b>한 트랜잭션</b>이라 공개
     * 화면은 철회와 발행 사이("철회된 안내")를 보지 않는다.
     *
     * <p>순서: 동네 · 주 잠금 → 없음 {@code 001} → 버전 {@code 006} → 발행 중이 아님 {@code 005} → 같은 동네 · 주에 다른 발행 중 안내 {@code 007} → 이전 안내
     * RETRACTED(메모 = 사유) → 새 안내 insert(근거 집계 · 동네 · 주 · 인용 수치를 이전 안내에서 복사, 상태 APPROVED) → 이력 (없음) → APPROVED → 발행
     * APPROVED → PUBLISHED. 정정 요청 자체를 운영자 승인으로 본다(entity-design §2-5 "(없음) → APPROVED"). 철회 시각과 새 안내의 발행 시각은 같은 순간이다
     * — 공개 동네 안내의 정정 시각(정정한 쪽의 발행 시각)이 철회 시각과 같다.
     *
     * <p>사유는 이전 안내의 철회 이력과 새 안내의 생성 이력에 함께 남는다 — 어느 쪽 상세에서도 왜 바뀌었는지 보인다. 저장소가 던지는 {@code 001} ·
     * {@code 006} 은 잡지 않는다(트랜잭션이 rollback-only 다). 도중에 무엇이 실패하든 철회까지 함께 롤백된다.
     *
     * @return 새 안내 (PUBLISHED)
     */
    public AdvisoryInfo correct(long operatorId, long advisoryId, AdvisoryCorrectCommand command) {
        LockedDistrictWeek locked = lockForTransition(operatorId, advisoryId, command.expectedVersion(), AdvisoryStatus.RETRACTED);
        Advisory current = locked.target();
        // 대상이 발행 중이면 다른 발행 중 안내는 규칙 위반 데이터다 — 정정이 발행 중 안내를 둘로 남기지 않게 막는다.
        if (locked.hasOtherPublished()) {
            throw new AdvisoryException(AdvisoryErrorCode.ALREADY_PUBLISHED, current.districtCode(), current.isoWeek().value());
        }
        LocalDateTime at = now();
        Advisory retracted = persist(current, current.retract(at), operatorId, command.reason());
        Advisory approved = advisoryRepositoryPort.insert(Advisory.newCorrection(snowflakeIdGenerator.generateId(), retracted, command.title(), command.body()));
        record(approved, null, operatorId, command.reason());
        return save(approved, approved.publish(at), operatorId, null);
    }

    /**
     * 같은 동네 · 주의 안내를 모두 잠그고(잠금 순서를 모든 전이에서 같게 — 교착 방지) 최신 값으로 확인한다. 잠근 뒤에 버전 · 전이를 보므로, 기다리는 사이
     * 다른 운영자가 커밋한 처리는 버전 불일치로 드러난다.
     */
    private LockedDistrictWeek lockForTransition(long operatorId, long advisoryId, long expectedVersion, AdvisoryStatus next) {
        requireOperator(operatorId);
        List<Advisory> advisories = advisoryRepositoryPort.lockDistrictWeekOf(advisoryId);
        Advisory current = advisories.stream()
            .filter(advisory -> advisory.id() == advisoryId)
            .findFirst()
            .orElseThrow(() -> new AdvisoryException(AdvisoryErrorCode.ADVISORY_NOT_FOUND));
        if (current.version() != expectedVersion) {
            throw new AdvisoryException(AdvisoryErrorCode.CONCURRENT_MODIFICATION);
        }
        if (!current.status().canTransitionTo(next)) {
            throw new AdvisoryException(AdvisoryErrorCode.INVALID_TRANSITION, current.status(), next);
        }
        return new LockedDistrictWeek(current, advisories);
    }

    /** 잠근 동네 · 주의 안내들 — 처리 대상과 같은 동네 · 주의 나머지(대상 포함). */
    private record LockedDistrictWeek(Advisory target, List<Advisory> advisories) {

        boolean hasOtherPublished() {
            return advisories.stream().anyMatch(advisory -> advisory.id() != target.id() && advisory.status() == AdvisoryStatus.PUBLISHED);
        }
    }

    /** 저장소가 충돌을 {@code ADVISORY_006} 으로 바꿔 던지면 이력도 남지 않는다 (같은 트랜잭션이 롤백된다). */
    private AdvisoryInfo save(Advisory current, Advisory next, long operatorId, String memo) {
        return AdvisoryInfo.from(persist(current, next, operatorId, memo));
    }

    private Advisory persist(Advisory current, Advisory next, long operatorId, String memo) {
        Advisory saved = advisoryRepositoryPort.update(next);
        record(saved, current.status(), operatorId, memo);
        return saved;
    }

    private void record(Advisory advisory, AdvisoryStatus fromStatus, long operatorId, String memo) {
        advisoryHistoryRepositoryPort.insert(AdvisoryHistory.operatorTransition(snowflakeIdGenerator.generateId(), advisory, fromStatus, operatorId, memo));
        log.info("advisory status changed advisoryId={} from={} to={} version={}", advisory.id(), fromStatus, advisory.status(), advisory.version());
    }

    private static void requireOperator(long operatorId) {
        if (operatorId <= 0) {
            throw new IllegalArgumentException("운영자 ID 는 양수여야 합니다.");
        }
    }

    /**
     * 발행 · 철회 시각. 집계 시각과 같은 기준(JVM 기본 시간대 지역 시각, 순간은 {@code Clock} 빈)이다. 초 단위로 자른다 — 컬럼이 MySQL
     * {@code TIMESTAMP}(초 정밀도)라, 자르지 않으면 이번 응답과 다시 읽은 값이 다르다(응답도 초 단위로 낸다, coding-conventions §2-2).
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS);
    }
}
