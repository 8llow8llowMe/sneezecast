package com.sneezecast.domainlayer.sentinelimport.application.model;

import java.time.LocalDate;

/**
 * 모든 요청이 성공한 실행의 요약. 하나라도 실패하면 결과 대신 {@code RUN_FAILED} 가 나간다.
 *
 * @param baseDate     계획의 기준일
 * @param requests     반영한 요청 수 (= 계획 요청 수 = IMPORTED 적재 이력 수)
 * @param importedRows upsert 로 보낸 행 수의 합
 * @param calls        감염병포털 호출 수 (화면 + 데이터)
 * @param nullValues   값이 null 인 행 수의 합 (원천이 비우거나 음수를 줬다)
 * @param pending      {@code 집계 중} 이라 행을 만들지 않은 칸 수의 합 (진행 중인 주 — 다음 실행이 다시 받는다)
 */
public record SentinelImportResult(LocalDate baseDate, int requests, int importedRows, int calls, int nullValues, int pending) {

}
