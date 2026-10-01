package com.sneezecast.domainlayer.districtimport.application.model;

/**
 * @param year     SGIS 기준 연도
 * @param fetched  스냅샷 읍면동 수
 * @param upserted upsert 로 보낸 행 수 (신규 + 갱신)
 * @param retired  이번 실행에서 폐지(valid_to_year) 처리한 행 수
 */
public record DistrictImportResult(int year, int fetched, int upserted, int retired) {

}
