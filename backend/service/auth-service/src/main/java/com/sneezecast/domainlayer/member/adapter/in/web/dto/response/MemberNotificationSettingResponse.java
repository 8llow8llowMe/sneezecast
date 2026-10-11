package com.sneezecast.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "알림 설정 응답 DTO — 설정 전부. 한 번도 바꾸지 않았으면 모두 false 다")
public record MemberNotificationSettingResponse(
    @Schema(description = "주간 보고 요청 알림(월요일 아침)을 받을지", example = "true")
    boolean weeklyReport,

    @Schema(description = "운영자가 검토 · 발행한 동네 안내 알림을 받을지", example = "false")
    boolean regionNotice
) {

}
