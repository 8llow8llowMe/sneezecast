package com.sneezecast.domainlayer.advisory.application.command;

/**
 * 철회 명령 → RETRACTED. 사유는 필수이고 이력({@code advisory_history.memo})에 남는다 — 동네 안내(#215)가 철회 표시에 쓸 수 있다.
 *
 * @param expectedVersion 화면이 읽은 안내 버전. 지금 버전과 다르면 409 {@code ADVISORY_006}
 * @param reason          철회 사유 (공백 불가)
 */
public record AdvisoryRetractCommand(
    long expectedVersion,
    String reason
) {

    public AdvisoryRetractCommand {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("철회 사유는 비어 있을 수 없습니다.");
        }
    }
}
