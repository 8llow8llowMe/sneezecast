package com.sneezecast.domainlayer.official.adapter.in.web.dto.item;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

/**
 * 한 감염병(계열)의 최신 기준 주. 기준 주는 <b>질병관리청 주차</b>(일요일 시작)라 시민 자가보고의 ISO 주와 하루 어긋난다 — 섞지 않는다.
 */
@Builder
@Schema(description = "감염병(병원체) 하나의 최신 기준 주 값")
public record OfficialDiseaseItem(
    @Schema(description = "감염병 키. 표본감시는 포털 병원체 코드(합계 TOTAL, 인플루엔자 분율 ILI), 전수신고는 감염병 이름", example = "ND0704")
    String diseaseKey,

    @Schema(description = "표시 이름 (원천 그대로)", example = "호흡기세포융합바이러스")
    String diseaseName,

    @Schema(description = "원천 분류 — 전수신고 제N급, 표본감시 세균 / 바이러스 / 원충. 없으면 null", nullable = true, example = "바이러스")
    String diseaseGroup,

    @Schema(description = "값의 종류 · 단위 (OfficialMetric — CASE_COUNT 신고 수 / ILI_PER_1000 외래 1,000명당 의사환자 분율)",
        example = "{\"code\":\"CASE_COUNT\",\"name\":\"발생 수\",\"description\":\"신고된 발생 건수\"}")
    CodeNameDescriptionMetadata metric,

    @Schema(description = "집계 단위 (OfficialRegionLevel). 행정동 단위가 아니다",
        example = "{\"code\":\"NATION\",\"name\":\"전국\",\"description\":\"전국 단위 값. 지역 코드는 00\"}")
    CodeNameDescriptionMetadata regionLevel,

    @Schema(description = "질병관리청 시도 코드 (SGIS 코드와 다르다). 전국이면 00", example = "00")
    String regionCode,

    @Schema(description = "지역 이름", example = "전국")
    String regionName,

    @Schema(description = "기준 주의 질병관리청 연도", example = "2026")
    int periodYear,

    @Schema(description = "기준 주의 질병관리청 주차 (일요일 시작, 1월 1일이 든 주가 1주). ISO 주가 아니다", example = "38")
    int periodWeek,

    @Schema(description = "기준 주 시작일 (일요일)", example = "2026-09-13")
    LocalDate periodStart,

    @Schema(description = "기준 주 종료일 (토요일)", example = "2026-09-19")
    LocalDate periodEnd,

    @Schema(description = "이 값을 마지막으로 적재한 시각 (ISO-8601 UTC, 초 단위). 적재가 실패 · 지연되면 오래된 시각 그대로다 — 화면이 보고 판단한다",
        example = "2026-09-24T21:00:04Z")
    Instant syncedAt,

    @Schema(description = "연령대별 값. 인플루엔자 분율은 연령대별(0세 ~ 65세 이상)만 오고 전체 값이 없다. 나머지는 연령 전체(ALL) 하나")
    List<OfficialValueItem> values
) {

}
