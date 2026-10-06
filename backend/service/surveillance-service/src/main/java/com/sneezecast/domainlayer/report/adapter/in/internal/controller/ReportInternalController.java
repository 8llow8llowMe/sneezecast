package com.sneezecast.domainlayer.report.adapter.in.internal.controller;

import com.sneezecast.domainlayer.report.application.exception.ReportValidationMessage;
import com.sneezecast.domainlayer.report.application.port.in.ReportInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 보고 API ({@code /internal/v1}, architecture-guide §4 고정 계약). 탈퇴 · 건강정보 동의 철회 때 auth 의 파기 스케줄러가 부른다
 * (entity-design §1-5).
 *
 * <p><b>인증 · 인가를 서비스에서 따로 검사하지 않는다</b> — 다른 {@code /internal/**} 과 같이 네트워크 격리(게이트웨이가 라우팅하지 않고, 서비스
 * 포트는 서버 루프백에만 publish)에 기댄다. 이 API 는 <b>되돌릴 수 없는 삭제</b>라, 격리가 깨지면(포트를 외부에 열거나 게이트웨이에 포괄 라우트를
 * 더하면) 회원 ID 만 아는 누구나 남의 보고를 지울 수 있다. 서비스 간 토큰을 도입하기 전에는 그 두 설정을 바꿀 때 이 경로를 함께 검토한다.
 * 실제 신뢰 경계는 그보다 넓다 — 게이트웨이 · Feign 이 붙는 공유 도커 네트워크({@code 8llow8llowme-net})의 다른 컨테이너와 같은 호스트(.13)의
 * 프로세스도 브리지 IP 로 이 포트에 닿는다. 그 동거자들을 신뢰한다는 전제이며, 깨지면 서비스 간 토큰(공유 비밀 헤더 등)으로 막는다.
 * auth 는 토큰 없이 부른다 — 유효하지 않은 Bearer 를 실으면 resource server 가 이 메서드에 닿기 전에 401 을 낸다.
 *
 * <p>응답은 <b>본문 없는 204</b> 다. 지운 건수와 상관없이 같다 — 0건이어도 성공이라 auth 는 완료될 때까지 마음 놓고 다시 부른다(멱등). 형식 오류만
 * {@code Response} 봉투 400 이다({@code ReportExceptionHandler}). 회원 ID · 가명 키는 응답 · 로그 어디에도 싣지 않는다.
 *
 * <p>공개 API 문서에 섞이면 프론트가 호출할 수 없는 경로를 보게 되므로 {@link Hidden} 으로 뺀다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/reporters")
public class ReportInternalController {

    private final ReportInternalUseCase reportInternalUseCase;

    @Operation(summary = "회원 원시 보고 파기 (auth → surveillance)", description = """
        회원의 주간 보고를 모든 주에 걸쳐 지웁니다. 지운 행이 없어도 204 입니다(멱등) — auth 파기 스케줄러가 완료될 때까지 다시 부릅니다.
        회원 ID 가 0 이하면 REPORT_106, 숫자가 아니면 REPORT_198(400) 입니다.

        호출 예: `DELETE /internal/v1/reporters/7350912846153`""")
    @DeleteMapping("/{memberId}")
    public ResponseEntity<Void> purgeReporter(
        @Parameter(description = "[필수] auth 회원 ID (양수)", required = true, example = "7350912846153")
        @PathVariable @Positive(message = ReportValidationMessage.MEMBER_ID_POSITIVE) long memberId) {
        reportInternalUseCase.purgeReporter(memberId);
        return ResponseEntity.noContent().build();
    }
}
