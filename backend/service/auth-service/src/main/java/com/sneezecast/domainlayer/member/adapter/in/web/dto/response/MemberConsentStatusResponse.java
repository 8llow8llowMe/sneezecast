package com.sneezecast.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "동의 상태 응답 DTO — 동의 · 철회 뒤의 상태. 로그인 · 재발급 · 내 정보와 같은 계산이다")
public record MemberConsentStatusResponse(
    @Schema(description = "다시 동의해야 하는 필수 항목(TERMS_OF_SERVICE · PRIVACY_POLICY 만). 없으면 빈 목록", example = "[]")
    List<String> pendingConsents,

    @Schema(description = "민감정보(건강정보) 동의가 지금 버전으로 유효한지", example = "true")
    boolean healthInfoAgreed,

    @Schema(description = "주간 보고를 쓸 수 있는지. 재동의 대기가 없고, 건강정보 동의가 유효하고, 미완료 보고 파기가 없어야 true. "
        + "access token 의 report:write 는 재발급(POST /api/v1/auth/token/reissue) 때 이 값을 따라온다", example = "true")
    boolean reportWritable,

    @Schema(description = "건강정보 동의 철회로 요청한 보고 파기가 아직 끝나지 않았는지. true 면 다시 동의해도 파기가 끝날 때까지 보고할 수 없다", example = "false")
    boolean purgePending
) {

}
