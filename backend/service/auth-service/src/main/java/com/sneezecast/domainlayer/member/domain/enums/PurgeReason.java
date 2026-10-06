package com.sneezecast.domainlayer.member.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * surveillance 원시 보고를 파기하라는 요청의 사유 (entity-design §1-5).
 */
@Getter
@RequiredArgsConstructor
public enum PurgeReason implements CodeNameDescribable {
    WITHDRAWAL("탈퇴", "회원 탈퇴. 회원 행은 이 사유의 파기가 끝날 때까지 남긴다"),
    HEALTH_CONSENT_WITHDRAWN("건강정보 동의 철회", "민감정보(건강정보) 동의 철회. 파기가 끝날 때까지 report:write 를 발급하지 않는다");

    private final String displayName;
    private final String description;
}
