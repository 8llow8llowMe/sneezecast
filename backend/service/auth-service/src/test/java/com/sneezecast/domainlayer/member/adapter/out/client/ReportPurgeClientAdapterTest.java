package com.sneezecast.domainlayer.member.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.domainlayer.member.adapter.out.client.feign.ReportPurgeClient;
import com.sneezecast.domainlayer.member.application.model.ReportPurgeCallResult;
import com.sneezecast.domainlayer.member.application.model.ReportPurgeCallResult.Outcome;
import com.sneezecast.global.client.FeignExceptionFixtures;
import com.sneezecast.global.client.InternalClientSupport;
import feign.Request.HttpMethod;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * surveillance 파기 응답 → 결과 갈래 변환을 본다. Feign 인터페이스는 mock 이고, 서킷 · 예외 판정은 실제 헬퍼와 레지스트리를 쓴다.
 * 예외는 실제 Feign 처럼 요청 URL(회원 ID 포함)을 메시지에 싣게 만들고, 그것이 결과 요약 · 로그로 새지 않는지 함께 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportPurgeClientAdapterTest {

    /** 다른 숫자와 우연히 겹치지 않는 긴 고유 ID — 로그 · 요약에 이 숫자가 없어야 한다. */
    private static final long MEMBER_ID = 7350912846153L;
    private static final String URL = "http://surveillance-service/internal/v1/reporters/" + MEMBER_ID;

    private ReportPurgeClient reportPurgeClient;
    private CircuitBreakerRegistry registry;
    private ReportPurgeClientAdapter adapter;

    @BeforeEach
    void setUp() {
        reportPurgeClient = mock(ReportPurgeClient.class);
        registry = FeignExceptionFixtures.smallRegistry();
        adapter = new ReportPurgeClientAdapter(reportPurgeClient, new InternalClientSupport(registry, new ObjectMapper()));
    }

    @Test
    @DisplayName("204(본문 없음 → null)는 파기 성공이다")
    void noContentIsPurged() {
        when(reportPurgeClient.purgeReporter(MEMBER_ID)).thenReturn(null);

        assertThat(adapter.purgeReporter(MEMBER_ID)).isEqualTo(ReportPurgeCallResult.purged());
    }

    @Test
    @DisplayName("봉투 없는 404(상대가 그 경로를 모르는 옛 버전)는 성공이 아니라 거절이다 — 상태와 결과 코드(null)를 남긴다")
    void routeNotFoundIsRejected(CapturedOutput output) {
        when(reportPurgeClient.purgeReporter(MEMBER_ID)).thenThrow(FeignExceptionFixtures.status(404,
            "{\"timestamp\":\"2026-10-08T00:00:00.000+00:00\",\"status\":404,\"error\":\"Not Found\",\"path\":\"/internal/v1/reporters/" + MEMBER_ID + "\"}",
            HttpMethod.DELETE, URL));

        ReportPurgeCallResult result = adapter.purgeReporter(MEMBER_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.errorSummary()).isEqualTo("REJECTED status=404 resultCode=null");
        assertThat(registry.circuitBreaker(InternalClientSupport.SURVEILLANCE_SERVICE).getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(output.getAll()).doesNotContain(String.valueOf(MEMBER_ID));
    }

    @Test
    @DisplayName("400 + 봉투는 결과 코드를 보존한 거절이다 — 응답 본문은 싣지 않는다")
    void badRequestKeepsResultCode() {
        when(reportPurgeClient.purgeReporter(MEMBER_ID)).thenThrow(FeignExceptionFixtures.status(400,
            "{\"dataHeader\":{\"success\":false,\"resultCode\":\"REPORT_106\",\"resultMessage\":\"secret-body-text\"},\"dataBody\":null}",
            HttpMethod.DELETE, URL));

        ReportPurgeCallResult result = adapter.purgeReporter(MEMBER_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.errorSummary()).isEqualTo("REJECTED status=400 resultCode=REPORT_106").doesNotContain("secret-body-text");
    }

    @Test
    @DisplayName("5xx 는 응답 없음이다 — 상태 코드만 남기고 URL · 본문은 남기지 않는다")
    void serverErrorIsUnavailable(CapturedOutput output) {
        when(reportPurgeClient.purgeReporter(MEMBER_ID)).thenThrow(FeignExceptionFixtures.status(503, "upstream down", HttpMethod.DELETE, URL));

        ReportPurgeCallResult result = adapter.purgeReporter(MEMBER_ID);

        assertThat(result).isEqualTo(new ReportPurgeCallResult(Outcome.UNAVAILABLE, "UNAVAILABLE status=503"));
        assertThat(output.getAll()).doesNotContain(String.valueOf(MEMBER_ID));
    }

    @Test
    @DisplayName("read timeout 은 응답 없음이다 — 상태가 없어 가장 안쪽 원인의 클래스 이름을 남긴다")
    void readTimeoutIsUnavailable(CapturedOutput output) {
        when(reportPurgeClient.purgeReporter(MEMBER_ID)).thenThrow(FeignExceptionFixtures.readTimeout(HttpMethod.DELETE, URL));

        ReportPurgeCallResult result = adapter.purgeReporter(MEMBER_ID);

        assertThat(result).isEqualTo(new ReportPurgeCallResult(Outcome.UNAVAILABLE, "UNAVAILABLE SocketTimeoutException"));
        assertThat(output.getAll()).doesNotContain(String.valueOf(MEMBER_ID));
    }

    @Test
    @DisplayName("서킷이 열려 있으면 호출하지 않고 서킷 오픈을 돌려준다")
    void circuitOpenSkipsCall() {
        registry.circuitBreaker(InternalClientSupport.SURVEILLANCE_SERVICE).transitionToOpenState();

        ReportPurgeCallResult result = adapter.purgeReporter(MEMBER_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.CIRCUIT_OPEN);
        verify(reportPurgeClient, never()).purgeReporter(anyLong());
    }
}
