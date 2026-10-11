package com.sneezecast.domainlayer.official.application.port.in;

import com.sneezecast.domainlayer.official.adapter.in.web.dto.response.OfficialLatestResponse;

public interface OfficialWebUseCase {

    /** 프로그램 × 감염병별 최신 기준 주. 자료가 없으면 {@code programs} 가 빈 목록이다 (null 이 아니다). */
    OfficialLatestResponse getLatest();
}
