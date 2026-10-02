package com.sneezecast.domainlayer.report.application.exception;

/**
 * 주간 보고 요청 검증 메시지 카탈로그 (REPORT_1xx).
 *
 * <p>Bean Validation 의 {@code message} 는 컴파일 상수만 받아 enum 을 직접 쓸 수 없다. 형식은 {@code "코드:사용자 메시지"} —
 * {@code ValidationErrorSupport} 가 접두어를 분리한다.
 */
public final class ReportValidationMessage {

    /** SGIS 읍면동 코드 형식 — 숫자 8자리 ({@code DistrictValidationMessage#DISTRICT_CODE_REGEXP} 와 같다). {@code \d} 는 ASCII 숫자만 받는다. */
    public static final String DISTRICT_CODE_REGEXP = "^\\d{8}$";

    // 빈 문자열 · 공백은 형식(REPORT_102)이 잡는다 — 같은 입력에 두 오류가 겹쳐 나오지 않게 필수는 @NotNull 로만 본다.
    public static final String DISTRICT_CODE_REQUIRED = "REPORT_101:행정동 코드를 입력해주세요.";
    public static final String DISTRICT_CODE_FORMAT_INVALID = "REPORT_102:행정동 코드는 숫자 8자리여야 합니다.";
    public static final String SYMPTOM_GROUPS_REQUIRED = "REPORT_103:증상군 목록을 보내주세요. 증상이 없으면 빈 목록입니다.";
    public static final String SYMPTOM_GROUP_ITEM_REQUIRED = "REPORT_104:증상군 값이 비어 있습니다.";
    public static final String SYMPTOM_GROUPS_DUPLICATED = "REPORT_105:같은 증상군을 두 번 보낼 수 없습니다.";

    private ReportValidationMessage() {
    }
}
