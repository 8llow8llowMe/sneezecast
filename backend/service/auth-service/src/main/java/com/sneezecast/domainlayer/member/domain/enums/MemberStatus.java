package com.sneezecast.domainlayer.member.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MemberStatus implements CodeNameDescribable {
    ACTIVE("정상", "서비스를 이용할 수 있는 회원"),
    WITHDRAWN("탈퇴", "탈퇴한 회원. 30일 보존 후, 보고 파기가 끝나면 파기한다"),
    SUSPENDED("정지", "운영자가 이용을 정지한 회원");

    private final String displayName;
    private final String description;
}
