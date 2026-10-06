package com.sneezecast.domainlayer.member.application.service.presenter;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberConsentStatusResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;
import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.info.MemberMyInfo;
import org.springframework.stereotype.Component;

/**
 * 회원 Info → 응답 DTO. ID 는 문자열로(coding-conventions §2-1), enum 은 {@code name()} 으로 바꾼다.
 */
@Component
public class MemberPresenter {

    /** 가입 방법 — 소셜 제공자가 없으면 이메일 계정이다. */
    static final String EMAIL_PROVIDER = "EMAIL";

    public MemberMyInfoResponse toMyInfoResponse(MemberMyInfo info) {
        return MemberMyInfoResponse.builder()
            .memberId(String.valueOf(info.memberId()))
            .email(info.email())
            .nickname(info.nickname())
            .provider(info.provider() == null ? EMAIL_PROVIDER : info.provider().name())
            .hasPassword(info.hasPassword())
            .role(info.role().name())
            .pendingConsents(info.pendingConsents().stream().map(Enum::name).toList())
            .reportWritable(info.reportWritable())
            .build();
    }

    /** @param reportWritable 같은 상태로 계산한 보고 가능 여부(auth 의 scope 정책) */
    public MemberConsentStatusResponse toConsentStatusResponse(MemberConsentStatusInfo status, boolean reportWritable) {
        return MemberConsentStatusResponse.builder()
            .pendingConsents(status.pendingRequiredConsents().stream().map(Enum::name).toList())
            .healthInfoAgreed(status.healthInfoAgreed())
            .reportWritable(reportWritable)
            .purgePending(status.purgePending())
            .build();
    }
}
