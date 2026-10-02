package com.sneezecast.domainlayer.notifiableimport.application.model;

/**
 * 모든 요청이 성공한 실행의 요약. 하나라도 실패하면 결과 대신 {@code RUN_FAILED} 가 나간다.
 *
 * @param currentYear  계획의 올해 (전년은 {@code currentYear - 1})
 * @param requests     반영한 요청 수 (= 계획 요청 수 = IMPORTED 적재 이력 수)
 * @param importedRows upsert 로 보낸 행 수의 합
 * @param calls        질병관리청 API 호출 수 (페이지 포함)
 * @param nullValues   값이 null 인 행 수의 합 (원천이 비우거나 숫자가 아닌 값을 줬다)
 */
public record NotifiableImportResult(int currentYear, int requests, int importedRows, int calls, int nullValues) {

}
