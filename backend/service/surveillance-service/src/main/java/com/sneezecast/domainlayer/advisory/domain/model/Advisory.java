package com.sneezecast.domainlayer.advisory.domain.model;

import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;
import lombok.Builder;

/**
 * 예방 안내 한 건 (entity-design §2-3). 상태를 바꾸는 메서드는 새 값을 돌려주고, 허용되지 않은 전이(§2-5)는 {@link IllegalStateException} 이다 —
 * 호출자(Processor)가 먼저 {@link AdvisoryStatus#canTransitionTo} 로 확인해 409 로 바꾸므로 여기까지 오면 프로그래밍 오류다.
 *
 * <p>상태와 시각의 관계를 생성 때 확인한다 — 발행 시각은 발행됨 · 철회됨에만, 철회 시각은 철회됨에만 있다.
 *
 * @param id                 Snowflake. 새 안내는 호출자가 정해서 넘긴다
 * @param aggregateId        근거 집계 (district_weekly_aggregate.id)
 * @param districtCode       대상 행정동
 * @param isoWeek            근거 집계 주
 * @param status             상태
 * @param title              제목 (현재본, {@value #TITLE_MAX_LENGTH}자 이하)
 * @param body               본문 (현재본)
 * @param citation           인용 수치 — 초안 생성 때 고정되고 바뀌지 않는다
 * @param correctsAdvisoryId 이 안내가 정정한 이전 안내({@link #newCorrection}). 정정 발행(#267)이 아니면 null
 * @param publishedAt        발행 시각
 * @param retractedAt        철회 시각
 * @param version            낙관적 락 버전 — 읽은 시점의 값. 저장하면 저장소가 올린 값으로 돌려준다
 * @param createdAt          처음 저장한 시각. 저장 전이면 null
 * @param updatedAt          마지막으로 고친 시각. 저장 전이면 null
 */
@Builder(toBuilder = true)
public record Advisory(
    long id,
    long aggregateId,
    String districtCode,
    ReportWeek isoWeek,
    AdvisoryStatus status,
    String title,
    String body,
    AdvisoryCitation citation,
    Long correctsAdvisoryId,
    LocalDateTime publishedAt,
    LocalDateTime retractedAt,
    long version,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    /** {@code advisory.title} VARCHAR(100). 문자(코드포인트) 수로 잰다. */
    public static final int TITLE_MAX_LENGTH = 100;

    /**
     * 발행 순서 — 발행 시각, 같으면 {@code id}(Snowflake, 시간 순). 같은 동네 · 주에 발행 중인 안내는 하나뿐이지만(entity-design §2-3,
     * {@code ADVISORY_007}) 그 규칙이 깨진 행이 있어도 공개 조회가 500 을 내지 않고 가장 최근 하나를 고르는 데 쓴다. 발행 시각이 없는 안내(발행 전)에
     * 쓰면 NPE 다.
     */
    public static final Comparator<Advisory> PUBLICATION_ORDER = Comparator.comparing(Advisory::publishedAt).thenComparingLong(Advisory::id);

    public Advisory {
        Objects.requireNonNull(districtCode, "districtCode");
        Objects.requireNonNull(isoWeek, "isoWeek");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(citation, "citation");
        requireContent(title, body);
        boolean published = status == AdvisoryStatus.PUBLISHED || status == AdvisoryStatus.RETRACTED;
        if (published != (publishedAt != null) || (status == AdvisoryStatus.RETRACTED) != (retractedAt != null)) {
            throw new IllegalArgumentException("안내 상태와 발행 · 철회 시각이 맞지 않습니다. id=" + id + ", status=" + status);
        }
    }

    /** 마감된 집계로 만든 새 초안. 감사 시각 · 버전은 저장할 때 정해진다. */
    public static Advisory newDraft(long id, long aggregateId, String districtCode, ReportWeek isoWeek, AdvisoryCitation citation, String title,
        String body) {
        return new Advisory(id, aggregateId, districtCode, isoWeek, AdvisoryStatus.DRAFT, title, body, citation, null, null, null, 0L, null, null);
    }

    /**
     * 정정 발행(#267)의 새 안내 → APPROVED. 정정 요청 자체가 운영자의 승인이다(entity-design §2-5 "(없음) → APPROVED"). 근거 집계 · 동네 · 주 · 인용 수치는
     * <b>이전 안내에서 옮긴다</b> — 집계가 다시 계산돼도 운영자가 검토한 같은 근거를 잇는다. 이전 안내는 먼저 철회돼 있어야 한다(같은 동네 · 주에 발행 중인
     * 안내는 하나뿐이라 철회가 먼저다).
     *
     * @throws IllegalStateException 이전 안내가 철회되지 않았다 — 호출 순서 오류
     */
    public static Advisory newCorrection(long id, Advisory previous, String title, String body) {
        if (previous.status() != AdvisoryStatus.RETRACTED) {
            throw new IllegalStateException("정정은 철회된 이전 안내로만 만든다. previousId=" + previous.id() + ", status=" + previous.status());
        }
        return new Advisory(id, previous.aggregateId(), previous.districtCode(), previous.isoWeek(), AdvisoryStatus.APPROVED, title, body,
            previous.citation(), previous.id(), null, null, 0L, null, null);
    }

    /** 제목 · 본문을 고친다 → EDITED. 승인된 안내를 고치면 승인이 풀린다 — 발행하려면 다시 승인받아야 한다. */
    public Advisory edit(String newTitle, String newBody) {
        return transition(AdvisoryStatus.EDITED).title(newTitle).body(newBody).build();
    }

    /** 현재 본문을 승인한다 → APPROVED. */
    public Advisory approve() {
        return transition(AdvisoryStatus.APPROVED).build();
    }

    /** 승인된 본문을 발행한다 → PUBLISHED. */
    public Advisory publish(LocalDateTime at) {
        return transition(AdvisoryStatus.PUBLISHED).publishedAt(Objects.requireNonNull(at, "at")).build();
    }

    /** 발행을 거둬들인다 → RETRACTED (끝 상태). 사유는 이력({@code advisory_history.memo})에 남긴다. */
    public Advisory retract(LocalDateTime at) {
        return transition(AdvisoryStatus.RETRACTED).retractedAt(Objects.requireNonNull(at, "at")).build();
    }

    private AdvisoryBuilder transition(AdvisoryStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("허용되지 않은 안내 상태 전이입니다. id=" + id + ", " + status + " -> " + next);
        }
        return toBuilder().status(next);
    }

    private static void requireContent(String title, String body) {
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("안내 제목은 비어 있지 않은 " + TITLE_MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("안내 본문은 비어 있을 수 없습니다.");
        }
    }
}
