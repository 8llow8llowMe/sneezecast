package com.sneezecast.domainlayer.districtimport.domain.model;

import java.util.List;

/**
 * 기준 연도 하나의 전국 읍면동 스냅샷.
 *
 * <p>{@code sidoCodes} 는 원천이 알려 준 시도 목록이다. 시도마다 읍면동이 1건 이상 있어야 하므로, 응답이 중간에 잘려 어떤 시도가 통째로
 * 비면 적재 전에 드러난다. 시도 목록을 코드에 박아 두지 않는 이유는 광주 · 전남 통합처럼 시도 코드 자체가 바뀔 수 있어서다.
 *
 * @param year      SGIS 기준 연도
 * @param sidoCodes 원천의 시도 코드 목록 (2자리)
 * @param districts 읍면동 목록
 */
public record DistrictSnapshot(int year, List<String> sidoCodes, List<ImportedDistrict> districts) {

    public DistrictSnapshot {
        sidoCodes = List.copyOf(sidoCodes);
        districts = List.copyOf(districts);
    }
}
