package com.sneezecast.domainlayer.advisory.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 예방 안내 상태 (entity-design §2-5 · §7). 허용 전이는 {@link #canTransitionTo} 한 곳에만 둔다 — 표에 없는 전이는 409 다.
 *
 * <p>{@link #EDITED} → {@link #EDITED}(다시 수정)는 §2-5 표에 행을 더한 것이다. 수정을 한 번만 받으면 두 번째 수정을 위해 승인부터 해야 한다.
 * {@link #AI_DRAFTED} 로 가는 경로(AI 초안)는 아직 만들지 않았지만 표는 그대로 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum AdvisoryStatus implements CodeNameDescribable {
    DRAFT("초안", "운영자가 마감된 집계로 만든 초안"),
    AI_DRAFTED("AI 초안", "인용값 스냅샷만으로 AI 가 다시 쓴 초안"),
    EDITED("수정됨", "운영자가 제목 · 본문을 고쳤다. 발행하려면 승인을 받아야 한다"),
    APPROVED("승인됨", "운영자가 현재 본문을 검토 · 승인했다. 승인 뒤 고치면 다시 승인받는다"),
    PUBLISHED("발행됨", "공개 안내로 보인다. 본문을 고치지 않는다 — 철회하고 새 안내를 만든다"),
    RETRACTED("철회됨", "발행을 거둬들였다. 끝 상태다");

    private final String displayName;
    private final String description;

    /**
     * 이 상태에서 {@code next} 로 갈 수 있는지 (§2-5 표). 생성(초안 → DRAFT, 정정 발행의 새 안내 → APPROVED)은 전이가 아니라 여기서 보지 않는다 — 어느
     * 상태에서도 DRAFT 로는 false 다.
     */
    public boolean canTransitionTo(AdvisoryStatus next) {
        return nextStatuses().contains(next);
    }

    private Set<AdvisoryStatus> nextStatuses() {
        return switch (this) {
            case DRAFT -> EnumSet.of(AI_DRAFTED, EDITED, APPROVED);
            case AI_DRAFTED -> EnumSet.of(EDITED, APPROVED);
            case EDITED -> EnumSet.of(EDITED, APPROVED);
            case APPROVED -> EnumSet.of(EDITED, PUBLISHED);
            case PUBLISHED -> EnumSet.of(RETRACTED);
            case RETRACTED -> EnumSet.noneOf(AdvisoryStatus.class);
        };
    }
}
