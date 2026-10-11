package com.sneezecast.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "프로필 이미지 업로드 응답 DTO")
public record MemberProfileImageResponse(
    @Schema(description = "방금 올린 프로필 이미지의 공개 URL. 내 정보 조회의 profileImageUrl 과 같은 값이다",
        example = "https://minio.8llow8llowme.com/sneezecast/members/profiles/1843956734582784/2026/10/3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61.png")
    String profileImageUrl
) {

}
