package com.sneezecast.domainlayer.districtimport.application.command;

/**
 * @param year            SGIS 기준 연도
 * @param allowMassRetire 사라지는 코드 비율이 임계값을 넘어도 폐지 처리를 진행할지. 운영자가 원인을 확인한 뒤에만 켠다
 */
public record DistrictImportCommand(int year, boolean allowMassRetire) {

}
