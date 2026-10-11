package com.sneezecast.domainlayer.advisory.application.command;

/**
 * 제목 · 본문 수정 명령 → EDITED. 승인된 안내를 고치면 다시 승인받아야 한다.
 *
 * @param expectedVersion 화면이 읽은 안내 버전. 지금 버전과 다르면 409 {@code ADVISORY_006}
 * @param title           고친 제목
 * @param body            고친 본문
 * @param memo            수정 사유 (선택). 이력에 남는다
 */
public record AdvisoryEditCommand(
    long expectedVersion,
    String title,
    String body,
    String memo
) {

}
