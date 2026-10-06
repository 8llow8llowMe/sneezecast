package com.sneezecast.domainlayer.member.application.info;

import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import java.util.List;
import lombok.Builder;

/**
 * 회원의 현재 동의 상태 (entity-design §1-2 "현재 유효한 동의" 기준).
 *
 * @param pendingRequiredConsents 다시 동의해야 하는 필수 항목 — 이용약관 · 개인정보만 들어간다(문서가 개정돼 현재 버전에 다시 동의해야 하는 경우 포함).
 *                                만 19세 확인은 가입 때 한 번 받는 사실 확인이라 버전을 보지 않고 여기 넣지 않는다. 순서는 {@link ConsentType} 선언 순서
 * @param healthInfoAgreed        민감정보(건강정보) 동의가 현재 버전으로 유효하고 철회되지 않았는지
 * @param purgePending            완료되지 않은 보고 파기 요청({@code report_purge_request.completed_at is null})이 있는지. 있으면 {@code report:write} 를
 *                                싣지 않는다
 */
@Builder
public record MemberConsentStatusInfo(
    List<ConsentType> pendingRequiredConsents,
    boolean healthInfoAgreed,
    boolean purgePending
) {

    public MemberConsentStatusInfo {
        pendingRequiredConsents = pendingRequiredConsents == null ? List.of() : List.copyOf(pendingRequiredConsents);
    }
}
