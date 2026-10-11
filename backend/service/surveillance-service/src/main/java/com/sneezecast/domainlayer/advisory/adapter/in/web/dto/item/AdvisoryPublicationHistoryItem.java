package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 발행 이력(#267, A03) 한 항목 — 발행됨 · 철회됨 안내 하나. 정정 관계는 양방향이다: {@code advisory.correctsAdvisoryId} 는 이 안내가 정정한 이전 안내,
 * {@code correctedByAdvisoryId} 는 이 안내를 정정한 새 안내다(정정됨으로 보이는 철회).
 */
@Builder
@Schema(description = "운영자 발행 이력 항목 DTO")
public record AdvisoryPublicationHistoryItem(
    @Schema(description = "안내 (상세의 advisory 와 같은 모양 — 인용 수치 · correctsAdvisoryId · version 포함)")
    AdvisoryOperatorResponse advisory,

    @Schema(description = "행정동 이름. 행정동 행이 없으면 null", example = "가락1동")
    String districtName,

    @Schema(description = "같은 이름의 동을 가르는 시도 · 시군구 표기. 행정동 행이 없으면 null", example = "서울특별시 송파구")
    String sigungu,

    @Schema(description = "이 안내를 정정한 새 안내 ID — 있으면 이 안내는 정정으로 내려간 것이다(철회가 아니라 정정됨). 없으면 null", example = "7250000000000000009")
    String correctedByAdvisoryId,

    @Schema(description = "생성 · 전이 · 수정 이력, 오래된 것부터 (상세의 histories 와 같은 모양). 정정 · 철회 사유(memo)는 운영자에게만 보인다")
    List<AdvisoryHistoryItem> histories
) {

}
