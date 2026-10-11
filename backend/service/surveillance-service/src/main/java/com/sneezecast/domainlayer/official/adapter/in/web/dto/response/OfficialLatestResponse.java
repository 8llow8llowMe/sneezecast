package com.sneezecast.domainlayer.official.adapter.in.web.dto.response;

import com.sneezecast.domainlayer.official.adapter.in.web.dto.item.OfficialProgramItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 질병관리청 감시 자료 최신 기준 주. 목록이 아니라 한 시점의 묶음이라 {@code SliceResponse} 가 아닌 객체로 둔다 — 발표 단계 · 요약처럼 출처가 정해진 뒤
 * 붙을 필드를 목록 모양을 깨지 않고 더할 수 있게 한다. <b>시민 자가보고 값은 싣지 않는다.</b>
 */
@Builder
@Schema(description = "질병관리청 감시 자료 최신 기준 주 응답 DTO")
public record OfficialLatestResponse(
    @Schema(description = "자료가 있는 프로그램만, 전수신고 → 인플루엔자 의사환자 → 급성호흡기감염증 → 장관감염증 순. 적재된 자료가 하나도 없으면 빈 배열")
    List<OfficialProgramItem> programs
) {

}
