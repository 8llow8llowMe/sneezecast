package com.sneezecast.domainlayer.official.adapter.in.web.dto.item;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "질병관리청 감시 프로그램 하나의 최신 기준 주")
public record OfficialProgramItem(
    @Schema(description = "감시 프로그램 (OfficialProgram — NOTIFIABLE / INFLUENZA_ILI / ARI / ENTERIC)",
        example = "{\"code\":\"ARI\",\"name\":\"급성호흡기감염증\",\"description\":\"급성호흡기감염증 병원체별 신고 수\"}")
    CodeNameDescriptionMetadata program,

    @Schema(description = "출처 (OfficialSource — KDCA_NOTIFIABLE 전수신고 / KDCA_SENTINEL 표본감시). 화면에 출처로 밝힌다(공공누리 제4유형)",
        example = "{\"code\":\"KDCA_SENTINEL\",\"name\":\"질병관리청 표본감시\",\"description\":\"감염병포털 표본감시 통계에서 받은 자료\"}")
    CodeNameDescriptionMetadata source,

    @Schema(description = "원문 주소 (질병관리청 감염병포털 화면). 확인된 주소가 없으면 null — 전수신고는 지금 null", nullable = true,
        example = "https://dportal.kdca.go.kr/pot/is/st/ari.do")
    String sourceUrl,

    @Schema(description = "감염병(병원체)별 최신 기준 주. 감염병 키 → 지표 → 지역 순")
    List<OfficialDiseaseItem> diseases
) {

}
