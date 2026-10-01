package com.sneezecast.domainlayer.member.domain.model;

import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * 동의 이력 한 줄. 문서가 개정되거나 철회 후 다시 동의하면 기존 행을 고치지 않고 새 행을 쌓는다 — "그때 무엇에 동의했는가"가 증거다.
 *
 * @param documentVersion 동의한 근거 문서의 버전 ({@code legal.*-version})
 * @param agreedAt        동의 시각. 행이 생긴 시각(createdAt)과 따로 둔다
 * @param withdrawnAt     철회 시각. 철회 가능한 항목만 채운다
 */
@Builder
public record MemberConsent(
    long id,
    long memberId,
    ConsentType type,
    String documentVersion,
    LocalDateTime agreedAt,
    LocalDateTime withdrawnAt
) {

}
