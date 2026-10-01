package com.sneezecast.domainlayer.auth.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.auth.application.exception.AuthValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 동의 · 확인 플래그는 <b>primitive {@code boolean}</b> 이어야 한다. {@code @AssertTrue} 는 null 을 유효로 보기 때문에 래퍼
 * {@code Boolean} 이면 필드를 빼고 보낸 요청이 검증을 통과해 동의 없이 가입된다. primitive 면 Jackson 이 누락을 {@code false} 로 채운다.
 *
 * <p>건강정보 동의({@code sensitiveHealthInfoAgreed})는 가입 동의와 <b>별도 필드</b>다 — 민감정보 별도 동의 요건(개인정보 보호법 제23조)이라
 * 화면 · 체크를 나누고, 가입 때는 선택이다. 누락은 미동의({@code false})로 본다.
 */
@Builder
@Schema(description = "이메일 회원가입 요청 DTO")
public record AuthGeneralSignupRequest(
    @Schema(description = "인증을 마친 이메일 주소 (100자 이하)", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.EMAIL_REQUIRED)
    @Size(max = 100, message = AuthValidationMessage.EMAIL_LENGTH_INVALID)
    @Email(message = AuthValidationMessage.EMAIL_FORMAT_INVALID)
    String email,

    @Schema(description = "비밀번호 (공백 없이 영문자 · 숫자 · 특수문자 각 1자 이상, 8~20자)", example = "P@ssw0rd!", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.PASSWORD_REQUIRED)
    @Size(min = 8, max = 20, message = AuthValidationMessage.PASSWORD_LENGTH_INVALID)
    @Pattern(regexp = AuthValidationMessage.PASSWORD_REGEXP, message = AuthValidationMessage.PASSWORD_PATTERN_INVALID)
    String password,

    @Schema(description = "닉네임 (10자 이하). 성명은 받지 않는다", example = "재채기탐정", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = AuthValidationMessage.NICKNAME_REQUIRED)
    @Size(max = 10, message = AuthValidationMessage.NICKNAME_LENGTH_INVALID)
    String nickname,

    @Schema(description = "이용약관 동의. 필수라 true 가 아니면 가입할 수 없다", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    @AssertTrue(message = AuthValidationMessage.TERMS_AGREEMENT_REQUIRED)
    boolean termsAgreed,

    @Schema(description = "개인정보 수집 · 이용 동의 (이메일 · 닉네임 · 프로필 이미지 · 행정동). 필수", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    @AssertTrue(message = AuthValidationMessage.PRIVACY_AGREEMENT_REQUIRED)
    boolean privacyAgreed,

    @Schema(description = "만 19세 이상 확인 (자기신고). 필수", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    @AssertTrue(message = AuthValidationMessage.AGE_OVER_19_REQUIRED)
    boolean ageOver19Confirmed,

    @Schema(description = "민감정보(건강정보) 수집 · 이용 별도 동의. 선택 — 동의하지 않아도 가입되고, 주간 보고 전에 따로 동의할 수 있다", example = "false")
    boolean sensitiveHealthInfoAgreed
) {

}
