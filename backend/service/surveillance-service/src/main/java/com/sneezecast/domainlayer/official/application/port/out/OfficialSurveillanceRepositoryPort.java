package com.sneezecast.domainlayer.official.application.port.out;

import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.model.OfficialObservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 질병관리청 감시 자료 읽기 포트. <b>저장 메서드를 두지 않는다</b> — 행의 쓰기 주체는 batch-service 적재 잡뿐이다 (modules.md batch-service).
 */
public interface OfficialSurveillanceRepositoryPort {

    /**
     * 주 단위(WEEK) 행 중 계열(원천 · 프로그램 · 감염병 키 · 지표 · 지역 단위 · 지역 코드)마다 가장 최근 기간의 행을 모두 준다 — 연령대별 행이 있으면
     * 그 주의 연령대 행을 전부 준다. 계열마다 기간이 다를 수 있다. 연 단위(YEAR) 행은 읽지 않는다. 순서는 정하지 않는다.
     *
     * @param periodStartLimits 프로그램별로 보일 수 있는 가장 늦은 기간 시작일(포함). 그보다 늦은 기간은 "가장 최근" 후보에서 뺀다. 없는 프로그램은 제한이 없다
     */
    List<OfficialObservation> findLatestWeekly(Map<OfficialProgram, LocalDate> periodStartLimits);
}
