package com.sneezecast.domainlayer.auth.adapter.in.web.dto.response;

import com.sneezecast.domainlayer.auth.adapter.in.web.dto.item.AuthSessionItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "로그인 기기(세션) 목록 응답 DTO")
public record AuthSessionsResponse(
    @Schema(description = "살아 있는 세션 목록 (마지막 사용 내림차순)")
    List<AuthSessionItem> sessions,

    @Schema(description = "세션 수", example = "2")
    int totalCount
) {

}
