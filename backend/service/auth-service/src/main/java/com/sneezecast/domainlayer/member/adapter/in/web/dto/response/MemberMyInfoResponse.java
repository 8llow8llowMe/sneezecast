package com.sneezecast.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "내 정보 응답 DTO (S10). 내 동네 · 프로필 이미지는 아직 싣지 않는다")
public record MemberMyInfoResponse(
    @Schema(description = "회원 아이디", example = "1843956734582784")
    String memberId,

    @Schema(description = "가입 이메일 (소문자)", example = "user@example.com")
    String email,

    @Schema(description = "닉네임", example = "재채기탐정")
    String nickname,

    @Schema(description = "로그인 방법 EMAIL · KAKAO — 카카오를 연결한 이메일 계정도 KAKAO 다. 비밀번호 로그인 가능 여부는 hasPassword 로 본다", example = "EMAIL")
    String provider,

    @Schema(description = "이메일 + 비밀번호로 로그인할 수 있는지. true 면 비밀번호 변경을 보여 주고, false 면(카카오로만 로그인) 비밀번호 메뉴를 보여 주지 않는다", example = "true")
    boolean hasPassword,

    @Schema(description = "역할 USER · OPERATOR · ADMIN", example = "USER")
    String role,

    @Schema(description = "다시 동의해야 하는 필수 항목(TERMS_OF_SERVICE · PRIVACY_POLICY 만). 로그인 · 재발급 응답과 같은 계산이다. 없으면 빈 목록", example = "[]")
    List<String> pendingConsents,

    @Schema(description = "주간 보고를 쓸 수 있는지. 재동의 대기가 없고, 건강정보 동의가 유효하고, 미완료 보고 파기가 없어야 true", example = "true")
    boolean reportWritable
) {

}
