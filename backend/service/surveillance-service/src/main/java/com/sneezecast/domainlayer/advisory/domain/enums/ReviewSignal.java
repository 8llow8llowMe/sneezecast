package com.sneezecast.domainlayer.advisory.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 예방 안내 검토 후보 신호 (entity-design §2-2 "검토 후보", #266). 조회 때 집계 행으로 계산하고 저장하지 않는다(후보 적재 {@code review_signal} 은 2단계).
 * 이름은 운영자 화면의 후보 유형({@code CANDIDATE_KIND_LABEL})과 거의 같지만 기준선만 다르다 — 화면 필터 라벨은 '기준선 변화', 서버는 상세 제목
 * ({@code CANDIDATE_KIND_TITLE})과 같은 '기준선 대비 변화' 다. 연동 때 맞춘다. 판정은 {@code ReviewCandidateRule} 한 곳이고, 응답은 이 선언 순서로 싣는다.
 */
@Getter
@RequiredArgsConstructor
public enum ReviewSignal implements CodeNameDescribable {
    BASELINE("기준선 대비 변화", "증상 보고 비율이 기준선보다 늘었다 — 집계 단계가 조금 늘었어요(SLIGHT) · 많이 늘었어요(HIGH)"),
    SURGE("참여 급증", "참여자 수가 전주보다 advisory.review-candidate.surge-change-percent(기본 30%) 이상 늘었다. 전주가 참여 부족이거나 없으면 보지 않는다"),
    REPEAT("반복 보고 의심", "그 주에 수정된 보고가 참여자의 advisory.review-candidate.repeat-revised-percent(기본 20%) 이상이다");

    private final String displayName;
    private final String description;
}
