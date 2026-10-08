package com.sneezecast.domainlayer.member.application.model;

/**
 * 파기 스케줄러 한 회차의 집계. 회차 끝 로그와 같은 값이다.
 *
 * @param due       이번 회차 대상 건수
 * @param completed 이번 회차에 완료된 건수
 * @param purged    파기는 성공했지만 아직 완료가 아닌 건수 (1차 파기 — 대기 시간 뒤 2차가 남았다)
 * @param failed    실패 건수 (거절 · 응답 없음 · 예상 밖 예외)
 * @param halted    서킷이 열려 회차를 중간에 멈췄는지
 */
public record ReportPurgeRunResult(int due, int completed, int purged, int failed, boolean halted) {

}
