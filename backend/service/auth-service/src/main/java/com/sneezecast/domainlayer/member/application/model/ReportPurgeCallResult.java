package com.sneezecast.domainlayer.member.application.model;

/**
 * surveillance 원시 보고 파기 호출 한 번의 결과 ({@code ReportPurgeCommandPort}). Feign · resilience4j 타입 대신 이 값으로 갈라 받는다.
 *
 * @param outcome      결과 갈래
 * @param errorSummary 실패 요약 — {@code last_error} 에 그대로 남긴다. 상태 코드 · 결과 코드 · 예외 클래스 단순 이름만 담고, 회원 ID · 요청 URL ·
 *                     응답 본문을 넣지 않는다 (동의 철회 사실과 회원이 이어지지 않게). 성공이면 null
 */
public record ReportPurgeCallResult(Outcome outcome, String errorSummary) {

    public enum Outcome {
        /** 2xx — 지울 행이 0건이어도 성공이다(멱등). */
        PURGED,
        /** 4xx — 상대가 요청을 읽고 거절했다(경로가 없는 404 = 상대가 옛 버전 포함). 성공으로 보지 않고 다시 부른다. */
        REJECTED,
        /** 5xx · timeout · 연결 실패 · 응답 해석 실패. 다시 부른다. */
        UNAVAILABLE,
        /** 서킷이 열려 호출이 나가지 않았다. 시도로 세지 않고 그 회차를 멈춘다. */
        CIRCUIT_OPEN
    }

    public static ReportPurgeCallResult purged() {
        return new ReportPurgeCallResult(Outcome.PURGED, null);
    }

    /** 예: {@code REJECTED status=404 resultCode=null} */
    public static ReportPurgeCallResult rejected(int status, String resultCode) {
        return new ReportPurgeCallResult(Outcome.REJECTED, "REJECTED status=" + status + " resultCode=" + resultCode);
    }

    /** 예: {@code UNAVAILABLE status=503}, {@code UNAVAILABLE SocketTimeoutException} */
    public static ReportPurgeCallResult unavailable(String detail) {
        return new ReportPurgeCallResult(Outcome.UNAVAILABLE, "UNAVAILABLE " + detail);
    }

    public static ReportPurgeCallResult circuitOpen() {
        return new ReportPurgeCallResult(Outcome.CIRCUIT_OPEN, "CIRCUIT_OPEN");
    }
}
