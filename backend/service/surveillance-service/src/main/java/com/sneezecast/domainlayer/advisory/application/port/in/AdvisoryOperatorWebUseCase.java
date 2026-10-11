package com.sneezecast.domainlayer.advisory.application.port.in;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorDetailResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryPublicationHistoryResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryReviewCandidateListResponse;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryCorrectCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryDraftCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryEditCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryRetractCommand;

/**
 * 운영자의 예방 안내 검토 · 승인 · 발행 (entity-design §2-3 · §2-5, 운영자 API #246). 운영자 ID 는 인증 주체에서 받은 auth {@code member.id}
 * (토큰 {@code sub})이고 역할(OPERATOR · ADMIN) 확인은 웹 경계({@code AdvisoryOperatorWebController} 의 {@code @PreAuthorize})가 한다.
 *
 * <p>전이 · 수정 요청은 화면이 읽은 {@code version} 을 함께 받는다. 다르면 409 {@code ADVISORY_006} 이고 화면은 다시 읽는다. 처리마다 이력이 한 행
 * 남는다. 오류 코드는 {@code AdvisoryErrorCode}. 승인과 발행은 따로 부른다 — 한 요청으로 묶지 않는다.
 */
public interface AdvisoryOperatorWebUseCase {

    /** 마감됐고 자료 부족이 아닌 동네 · 주 집계로 초안을 만든다 → DRAFT. 집계 없음 404 · 마감 전 · 자료 부족 409. */
    AdvisoryOperatorResponse createDraft(long operatorId, AdvisoryDraftCommand command);

    /** 제목 · 본문 수정 → EDITED. DRAFT · AI_DRAFTED · EDITED · APPROVED 에서만 된다 (APPROVED 면 다시 승인받아야 한다). */
    AdvisoryOperatorResponse edit(long operatorId, long advisoryId, AdvisoryEditCommand command);

    /** 승인 → APPROVED. DRAFT · AI_DRAFTED · EDITED 에서만 된다. */
    AdvisoryOperatorResponse approve(long operatorId, long advisoryId, long expectedVersion);

    /** 발행 → PUBLISHED. APPROVED 에서만 되고, 같은 동네 · 주에 발행 중인 안내가 있으면 409. */
    AdvisoryOperatorResponse publish(long operatorId, long advisoryId, long expectedVersion);

    /** 철회 → RETRACTED. PUBLISHED 에서만 된다. 사유 필수. */
    AdvisoryOperatorResponse retract(long operatorId, long advisoryId, AdvisoryRetractCommand command);

    /**
     * 정정 발행(#267) — 발행 중인 안내를 철회하고(사유 필수) 고친 제목 · 본문으로 새 안내를 발행해 잇는다. <b>한 요청 · 한 트랜잭션</b>이고 정정 요청 자체가
     * 승인이다(새 안내 이력: (없음) → APPROVED → PUBLISHED). PUBLISHED 에서만 되고, 같은 동네 · 주에 다른 발행 중인 안내가 있으면 409.
     *
     * @return 새 안내 (PUBLISHED, {@code correctsAdvisoryId} = {@code advisoryId})
     */
    AdvisoryOperatorResponse correct(long operatorId, long advisoryId, AdvisoryCorrectCommand command);

    /** 안내 한 건과 전 이력(오래된 것부터). 없는 안내 404. */
    AdvisoryOperatorDetailResponse getDetail(long advisoryId);

    /**
     * 한 주의 검토 후보(#266) — 참여 급증 · 반복 보고 · 기준선 대비 변화 신호가 있거나 그 주에 안내가 있는 동네. 자료 부족 동네는 수치 없이 보이고 초안을 만들 수
     * 없다고 표시한다.
     *
     * @param week {@code YYYY-Www} 형식이 확인된 주. null 이면 현재 주 − 1. 조회 창 밖이면 400 {@code ADVISORY_009}
     */
    AdvisoryReviewCandidateListResponse getCandidates(String week);

    /**
     * 발행 이력(#267, A03) — {@code week} 로 끝나는 4주의 발행됨 · 철회됨 안내, 발행 시각 최근 것부터. 동 이름 · 인용 수치 · 정정 관계(양방향) · 전 이력을 싣는다.
     *
     * @param week {@code YYYY-Www} 형식이 확인된 끝 주. null 이면 현재 주 − 1. 조회 창 밖이면 400 {@code ADVISORY_009}
     */
    AdvisoryPublicationHistoryResponse getPublicationHistory(String week);
}
