package com.sneezecast.domainlayer.region.application.exception;

/**
 * 내 동네 요청 검증 메시지 카탈로그 (REGION_1xx).
 *
 * <p>Bean Validation 의 {@code message} 는 컴파일 상수만 받아 enum 을 직접 쓸 수 없다. 코드와 메시지를 여기 모아 DTO 가 참조하게
 * 하면 오타 · 삭제를 컴파일러가 잡는다. 형식은 {@code "코드:사용자 메시지"} — {@code ValidationErrorSupport} 가 접두어를 분리한다.
 */
public final class RegionValidationMessage {

    /**
     * SGIS 읍면동 코드({@code adm_cd}) 형식 — 숫자 8자리. surveillance {@code DistrictValidationMessage.DISTRICT_CODE_REGEXP} 와 같다. 여기서
     * 먼저 막아 잘못된 입력으로 surveillance 를 부르지 않는다. {@code \d} 는 Java 기본 모드에서 ASCII 숫자만 받는다.
     */
    public static final String DISTRICT_CODE_REGEXP = "^\\d{8}$";

    // 빈 문자열 · 공백은 형식(REGION_102)이 잡는다 — 같은 입력에 두 오류가 겹쳐 나오지 않게 필수는 @NotNull 로만 본다.
    public static final String DISTRICT_CODE_REQUIRED = "REGION_101:행정동 코드는 필수입니다.";
    public static final String DISTRICT_CODE_FORMAT_INVALID = "REGION_102:행정동 코드는 숫자 8자리여야 합니다.";

    private RegionValidationMessage() {
    }
}
