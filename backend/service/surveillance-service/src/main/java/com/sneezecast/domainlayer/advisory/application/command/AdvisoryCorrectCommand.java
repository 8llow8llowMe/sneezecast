package com.sneezecast.domainlayer.advisory.application.command;

/**
 * 정정 발행 명령(#267) — 발행 중인 안내를 철회하고 고친 제목 · 본문으로 새 안내를 발행해 둘을 잇는다. 사유는 이전 안내의 철회 이력과 새 안내의 생성 이력
 * ({@code advisory_history.memo})에 남고 운영자에게만 보인다. 제목 · 본문 조건은 도메인({@code Advisory})이 본다(수정 명령과 같다).
 *
 * @param expectedVersion 화면이 읽은 <b>이전 안내</b>의 버전. 지금 버전과 다르면 409 {@code ADVISORY_006}
 * @param title           새 안내의 제목 (전체 값)
 * @param body            새 안내의 본문 (전체 값 — 고치지 않고 사유만 남기는 정정도 된다)
 * @param reason          정정 사유 (공백 불가)
 */
public record AdvisoryCorrectCommand(
    long expectedVersion,
    String title,
    String body,
    String reason
) {

    public AdvisoryCorrectCommand {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("정정 사유는 비어 있을 수 없습니다.");
        }
    }
}
