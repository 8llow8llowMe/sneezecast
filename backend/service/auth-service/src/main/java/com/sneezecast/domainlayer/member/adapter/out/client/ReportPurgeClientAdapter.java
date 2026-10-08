package com.sneezecast.domainlayer.member.adapter.out.client;

import com.sneezecast.domainlayer.member.adapter.out.client.feign.ReportPurgeClient;
import com.sneezecast.domainlayer.member.application.model.ReportPurgeCallResult;
import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeCommandPort;
import com.sneezecast.global.client.InternalClientRejectedException;
import com.sneezecast.global.client.InternalClientSupport;
import com.sneezecast.global.client.InternalServiceUnavailableException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * surveillance 원시 보고 파기 호출. 응답을 갈래로 바꿔 돌려주고, 예외 · Feign · resilience4j 타입을 이 밖으로 내보내지 않는다.
 *
 * <ul>
 *   <li>2xx(본문 없는 204) → {@code PURGED}</li>
 *   <li>4xx → {@code REJECTED}. 404 도 성공으로 보지 않는다 — 봉투 없는 404 는 상대가 아직 그 경로를 모르는 옛 버전이라 아무것도 지우지 않았다.</li>
 *   <li>5xx · timeout · 연결 실패 · 응답 해석 실패 → {@code UNAVAILABLE}</li>
 *   <li>서킷 오픈 → {@code CIRCUIT_OPEN} (호출이 나가지 않았다)</li>
 * </ul>
 *
 * <p><b>여기서 로그를 남기지 않고, 예외 메시지 · 스택을 어디에도 옮기지 않는다.</b> {@code FeignException} 메시지에는
 * {@code [DELETE] to [http://.../internal/v1/reporters/{memberId}]} 가 들어 있어 건강정보 동의 철회 사실과 회원 ID 가 이어진다. 두 래퍼 예외도
 * 그것을 cause 로 들고 있다. 실패 요약은 상태 코드 · 결과 코드 · 예외 클래스 단순 이름으로만 만든다.
 */
@Component
@RequiredArgsConstructor
public class ReportPurgeClientAdapter implements ReportPurgeCommandPort {

    private final ReportPurgeClient reportPurgeClient;
    private final InternalClientSupport internalClientSupport;

    @Override
    public ReportPurgeCallResult purgeReporter(long memberId) {
        try {
            internalClientSupport.requestAndUnwrap(InternalClientSupport.SURVEILLANCE_SERVICE, () -> reportPurgeClient.purgeReporter(memberId));
            return ReportPurgeCallResult.purged();
        } catch (InternalClientRejectedException exception) {
            return ReportPurgeCallResult.rejected(exception.getStatus(), exception.getResultCode());
        } catch (InternalServiceUnavailableException exception) {
            return unavailable(exception.getCause());
        }
    }

    private static ReportPurgeCallResult unavailable(Throwable cause) {
        if (cause instanceof CallNotPermittedException) {
            return ReportPurgeCallResult.circuitOpen();
        }
        if (cause instanceof FeignException feignException && feignException.status() > 0) {
            return ReportPurgeCallResult.unavailable("status=" + feignException.status());
        }
        if (cause == null) {
            return ReportPurgeCallResult.unavailable("unknown");
        }
        // 상태가 없는 실패(connect · read timeout, 연결 거부)는 Feign 이 -1 로 올린다. 가장 안쪽 원인의 클래스 이름으로 가른다.
        return ReportPurgeCallResult.unavailable(rootCause(cause).getClass().getSimpleName());
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
