package com.sneezecast.domainlayer.official.adapter.out.persistence.repository.custom;

import com.sneezecast.domainlayer.official.adapter.out.persistence.entity.OfficialSurveillanceEntity;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface OfficialSurveillanceCustomRepository {

    /**
     * 주 단위 행 중 계열(원천 · 프로그램 · 감염병 키 · 지표 · 지역 단위 · 지역 코드)마다 가장 최근 기간의 행 (연령대 행은 모두).
     *
     * @param periodStartLimits 프로그램별로 보일 수 있는 가장 늦은 기간 시작일(포함). 없는 프로그램은 제한이 없다
     */
    List<OfficialSurveillanceEntity> findLatestWeekly(Map<OfficialProgram, LocalDate> periodStartLimits);
}
