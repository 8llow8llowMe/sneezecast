package com.sneezecast.domainlayer.member.application.info;

import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import java.util.List;
import lombok.Builder;

/**
 * 회원의 현재 동의 상태 (entity-design §1-2 "현재 유효한 동의" 기준).
 *
 * @param pendingRequiredConsents 유효한 동의가 없는 필수 항목(이용약관 · 개인정보 · 만 19세 이상). 문서가 개정돼 현재 버전에 다시 동의해야
 *                                하는 경우도 여기 들어간다. 순서는 {@link ConsentType} 선언 순서
 * @param healthInfoAgreed        민감정보(건강정보) 동의가 현재 버전으로 유효하고 철회되지 않았는지
 */
@Builder
public record MemberConsentStatusInfo(
    List<ConsentType> pendingRequiredConsents,
    boolean healthInfoAgreed
) {

    public MemberConsentStatusInfo {
        pendingRequiredConsents = pendingRequiredConsents == null ? List.of() : List.copyOf(pendingRequiredConsents);
    }
}
