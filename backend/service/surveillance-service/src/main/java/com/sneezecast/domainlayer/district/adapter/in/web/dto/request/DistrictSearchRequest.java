package com.sneezecast.domainlayer.district.adapter.in.web.dto.request;

import com.sneezecast.domainlayer.district.application.exception.DistrictValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 행정동 검색 쿼리 파라미터 ({@code ?query=}).
 *
 * <p>검증 전에 앞뒤 공백을 걷어 낸다 — 길이 규칙(1~20자)이 공백을 뺀 검색어 기준이라, 원문에 {@code @Size} 를 걸면 앞뒤 공백 때문에
 * 21자가 된 정상 검색어가 막힌다. 프론트 목(region-client)도 {@code query.trim()} 한 값으로 찾는다.
 */
public record DistrictSearchRequest(
    @Schema(description = "[필수] 검색어. 동 이름 또는 시도 · 시군구 이름의 일부. 앞뒤 공백은 무시하고 1~20자", example = "역삼",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = DistrictValidationMessage.SEARCH_QUERY_REQUIRED)
    @Size(max = DistrictValidationMessage.SEARCH_QUERY_MAX_LENGTH, message = DistrictValidationMessage.SEARCH_QUERY_LENGTH_INVALID)
    String query
) {

    public DistrictSearchRequest {
        query = query == null ? null : query.strip();
    }
}
