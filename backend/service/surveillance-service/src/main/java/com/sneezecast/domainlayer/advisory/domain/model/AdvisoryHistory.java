package com.sneezecast.domainlayer.advisory.domain.model;

import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 예방 안내 상태 전이 · 수정 이력 한 행 (entity-design §2-4). 쌓기만 하고 고치지 않는다. 제목 · 본문은 그 시점의 값이라 무엇이 승인 · 발행됐는지
 * 복원할 수 있다.
 *
 * <p>{@code operatorId} 는 surveillance 가 회원 ID 를 갖는 유일한 예외다(운영자 감사, modules.md). 보고자와는 무관하다.
 *
 * @param id            Snowflake
 * @param advisoryId    안내
 * @param fromStatus    이전 상태. 생성이면 null
 * @param toStatus      바뀐 상태
 * @param title         이 시점의 제목
 * @param body          이 시점의 본문
 * @param operatorId    요청한 운영자 (auth member.id, 양수)
 * @param aiModel       AI 초안을 만든 모델 id — AI_DRAFTED 전이에만 (아직 경로 없음)
 * @param promptVersion AI 초안 프롬프트 버전 — AI_DRAFTED 전이에만 (아직 경로 없음)
 * @param memo          수정 · 철회 · 정정 사유(정정은 이전 안내 철회 행과 새 안내 생성 행 둘 다) ({@value #MEMO_MAX_LENGTH}자 이하)
 * @param createdAt     전이 시각. 저장 전이면 null
 */
public record AdvisoryHistory(
    long id,
    long advisoryId,
    AdvisoryStatus fromStatus,
    AdvisoryStatus toStatus,
    String title,
    String body,
    long operatorId,
    String aiModel,
    String promptVersion,
    String memo,
    LocalDateTime createdAt
) {

    /** {@code advisory_history.memo} VARCHAR(500). 문자(코드포인트) 수로 잰다. */
    public static final int MEMO_MAX_LENGTH = 500;

    public AdvisoryHistory {
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        if (operatorId <= 0) {
            throw new IllegalArgumentException("운영자 ID 는 양수여야 합니다.");
        }
        if (memo != null && memo.codePointCount(0, memo.length()) > MEMO_MAX_LENGTH) {
            throw new IllegalArgumentException("이력 메모는 " + MEMO_MAX_LENGTH + "자 이하여야 합니다.");
        }
    }

    /**
     * 운영자 요청으로 안내가 {@code fromStatus} 에서 지금 상태({@code advisory.status()})가 된 기록. 제목 · 본문은 바뀐 뒤의 값을 남긴다.
     *
     * @param fromStatus 이전 상태. 생성이면 null
     * @param memo       수정 · 철회 · 정정 사유. 없으면 null
     */
    public static AdvisoryHistory operatorTransition(long id, Advisory advisory, AdvisoryStatus fromStatus, long operatorId, String memo) {
        return new AdvisoryHistory(id, advisory.id(), fromStatus, advisory.status(), advisory.title(), advisory.body(), operatorId, null, null, memo, null);
    }
}
