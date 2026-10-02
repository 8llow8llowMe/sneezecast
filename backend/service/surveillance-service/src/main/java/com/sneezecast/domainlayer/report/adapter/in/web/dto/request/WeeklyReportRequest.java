package com.sneezecast.domainlayer.report.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.report.application.exception.ReportValidationMessage;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.Builder;
import org.hibernate.validator.constraints.UniqueElements;

/**
 * 이번 주 보고 제출 본문. <b>보고 주를 받지 않는다</b> — 서버가 요청 시각(KST)으로 정한다 (entity-design §2-1). 보낸 주 값 같은 모르는 필드는 무시된다.
 *
 * <p>{@code symptomGroups} 는 null 을 거부하고 빈 배열을 받는다 — 빈 배열이 "증상 없음" 이고, 건강한 주간 보고가 집계의 분모라 정상 보고다.
 * null 원소 · 중복 원소는 조용히 접지 않고 거부한다 (coding-conventions §6-2). 알 수 없는 값은 역직렬화에서 {@code REPORT_100} 이다.
 */
@Builder
public record WeeklyReportRequest(
    @Schema(description = "[필수] 보고 행정동 코드 (SGIS 숫자 8자리). 보고 주는 서버가 정한다", example = "11230510", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = ReportValidationMessage.DISTRICT_CODE_REQUIRED)
    @Pattern(regexp = ReportValidationMessage.DISTRICT_CODE_REGEXP, message = ReportValidationMessage.DISTRICT_CODE_FORMAT_INVALID)
    String districtCode,

    @Schema(description = "[필수] 증상군 코드 목록 (RESPIRATORY · ENTERIC). 증상 없음이면 빈 배열. 같은 값을 두 번 보내지 않는다", example = "[\"RESPIRATORY\"]",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ReportValidationMessage.SYMPTOM_GROUPS_REQUIRED)
    @UniqueElements(message = ReportValidationMessage.SYMPTOM_GROUPS_DUPLICATED)
    List<@NotNull(message = ReportValidationMessage.SYMPTOM_GROUP_ITEM_REQUIRED) SymptomGroup> symptomGroups
) {

    /** 증상(민감정보)을 로그에 흘리지 않는다. */
    @Override
    public String toString() {
        return "WeeklyReportRequest[districtCode=" + districtCode + ", symptomGroups=****]";
    }
}
