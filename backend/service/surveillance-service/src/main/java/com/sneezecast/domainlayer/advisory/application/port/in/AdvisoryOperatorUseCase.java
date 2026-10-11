package com.sneezecast.domainlayer.advisory.application.port.in;

import com.sneezecast.domainlayer.advisory.application.command.AdvisoryDraftCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryEditCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryRetractCommand;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryDetailInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryInfo;

/**
 * 운영자의 예방 안내 검토 · 승인 · 발행 (entity-design §2-3 · §2-5). 운영자 ID 는 인증 주체에서 받은 auth {@code member.id} 이고 역할(OPERATOR ·
 * ADMIN) 확인은 웹 경계가 한다 — 운영자 API(#246)가 이 포트에 컨트롤러를 붙이며 반환을 Response(Presenter)로 바꾼다. 아직 웹 경계가 없어
 * {@code *WebUseCase} 가 아니다.
 *
 * <p>전이 · 수정 요청은 화면이 읽은 {@code version} 을 함께 받는다. 다르면 409 {@code ADVISORY_006} 이고 화면은 다시 읽는다. 처리마다 이력이 한 행
 * 남는다. 오류 코드는 {@code AdvisoryErrorCode}.
 */
public interface AdvisoryOperatorUseCase {

    /** 마감됐고 자료 부족이 아닌 동네 · 주 집계로 초안을 만든다 → DRAFT. 집계 없음 404 · 마감 전 · 자료 부족 409. */
    AdvisoryInfo createDraft(long operatorId, AdvisoryDraftCommand command);

    /** 제목 · 본문 수정 → EDITED. DRAFT · AI_DRAFTED · EDITED · APPROVED 에서만 된다 (APPROVED 면 다시 승인받아야 한다). */
    AdvisoryInfo edit(long operatorId, long advisoryId, AdvisoryEditCommand command);

    /** 승인 → APPROVED. DRAFT · AI_DRAFTED · EDITED 에서만 된다. */
    AdvisoryInfo approve(long operatorId, long advisoryId, long expectedVersion);

    /** 발행 → PUBLISHED. APPROVED 에서만 되고, 같은 동네 · 주에 발행 중인 안내가 있으면 409. */
    AdvisoryInfo publish(long operatorId, long advisoryId, long expectedVersion);

    /** 철회 → RETRACTED. PUBLISHED 에서만 된다. 사유 필수. */
    AdvisoryInfo retract(long operatorId, long advisoryId, AdvisoryRetractCommand command);

    /** 안내 한 건과 전 이력(오래된 것부터). 없는 안내 404. */
    AdvisoryDetailInfo getDetail(long advisoryId);
}
