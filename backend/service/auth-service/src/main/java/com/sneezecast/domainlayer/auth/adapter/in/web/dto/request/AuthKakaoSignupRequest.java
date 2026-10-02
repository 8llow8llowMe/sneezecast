package com.sneezecast.domainlayer.auth.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.auth.application.exception.AuthValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import lombok.Builder;

/**
 * 카카오 가입 요청 DTO. 이메일 · 닉네임은 받지 않는다 — 카카오에서 받은 값을 가입표(쿠키)로 서버가 들고 있다.
 *
 * <p>동의 · 확인 플래그는 <b>primitive {@code boolean}</b> 이어야 한다 ({@link AuthGeneralSignupRequest} 와 같은 이유 — 래퍼면 필드를 빼고 보낸 요청이
 * {@code @AssertTrue} 를 통과한다). 건강정보 동의는 이메일 가입과 같이 가입 뒤 별도 API 로 받는다.
 */
@Builder
@Schema(description = "카카오 가입 요청 DTO")
public record AuthKakaoSignupRequest(
    @Schema(description = "이용약관 동의. 필수라 true 가 아니면 가입할 수 없다", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    @AssertTrue(message = AuthValidationMessage.TERMS_AGREEMENT_REQUIRED)
    boolean termsAgreed,

    @Schema(description = "개인정보 수집 · 이용 동의 (이메일 · 닉네임 · 행정동). 필수", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    @AssertTrue(message = AuthValidationMessage.PRIVACY_AGREEMENT_REQUIRED)
    boolean privacyAgreed,

    @Schema(description = "만 19세 이상 확인 (자기신고). 필수", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    @AssertTrue(message = AuthValidationMessage.AGE_OVER_19_REQUIRED)
    boolean ageOver19Confirmed
) {

}
