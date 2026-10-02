package com.sneezecast.domainlayer.auth.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthEmailCodeSendRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthEmailCodeVerifyRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthGeneralLoginRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthGeneralSignupRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthSessionsResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.ClientIpResolver;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.DeviceLabelResolver;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.RefreshCookieProvider;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthValidationMessage;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.port.in.AuthWebUseCase;
import com.sneezecast.security.common.dto.MemberLoginActive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "이메일 인증, 회원가입, 로그인 · 토큰 재발급 · 로그아웃, 로그인 기기(세션) API")
public class AuthWebController {

    private final AuthWebUseCase authWebUseCase;
    private final ClientIpResolver clientIpResolver;
    private final DeviceLabelResolver deviceLabelResolver;
    private final RefreshCookieProvider refreshCookieProvider;

    @Operation(summary = "이메일 인증코드 발송", description = """
        회원가입용 이메일 인증코드(숫자 6자리)를 메일로 보냅니다.
        이메일당 재발송 쿨다운(AUTH_001, 429)과 IP당 발송 상한(AUTH_002, 429)이 있습니다.
        응답은 가입 여부와 무관하게 항상 같습니다. 이미 가입된 이메일이면 메일함으로 안내 메일이 갑니다.

        인증 불필요. **필수: 요청 바디의 email.**

        호출 예: `POST /api/v1/auth/email/send-code` `{"email":"user@example.com"}`""")
    @PostMapping("/email/send-code")
    public ResponseEntity<Response<Void>> sendEmailVerificationCode(@Valid @RequestBody AuthEmailCodeSendRequest request, HttpServletRequest httpServletRequest) {
        authWebUseCase.sendEmailVerificationCode(request.email(), clientIpResolver.resolve(httpServletRequest));
        return ResponseEntity.ok(Response.success());
    }

    @Operation(summary = "이메일 인증코드 검증", description = """
        메일로 받은 인증코드를 검증합니다. 성공하면 정해진 시간(기본 30분) 안에 그 이메일로 가입할 수 있습니다.
        코드 불일치는 AUTH_003, 코드가 없거나 만료됐으면 AUTH_004, 정해진 횟수(기본 5회) 틀리면 코드가 무효화되고 AUTH_005,
        IP당 검증 상한을 넘으면 AUTH_010(429) 입니다. 어느 응답도 가입 여부를 뜻하지 않습니다. 앞뒤 공백은 무시합니다.

        인증 불필요. **필수: 요청 바디의 email, code.**

        호출 예: `POST /api/v1/auth/email/verify-code` `{"email":"user@example.com","code":"482913"}`""")
    @PostMapping("/email/verify-code")
    public ResponseEntity<Response<Void>> verifyEmailVerificationCode(@Valid @RequestBody AuthEmailCodeVerifyRequest request, HttpServletRequest httpServletRequest) {
        authWebUseCase.verifyEmailVerificationCode(request.email(), request.code(), clientIpResolver.resolve(httpServletRequest));
        return ResponseEntity.ok(Response.success());
    }

    @Operation(summary = "이메일 회원가입", description = """
        이메일 인증을 마친 뒤 정해진 시간(기본 30분) 안에 가입합니다. 가입만 하고 토큰은 주지 않습니다 — 이어서 로그인합니다.
        미인증 이메일은 AUTH_007, 이미 가입된 이메일은 MEMBER_001(409) 입니다.

        필수 체크 셋은 각각 다른 코드로 막힙니다 — 이용약관 AUTH_110, 개인정보 수집 · 이용 AUTH_111, 만 19세 이상 확인 AUTH_112.
        건강정보(민감정보) 동의 `sensitiveHealthInfoAgreed` 는 가입 동의와 별도의 선택 항목입니다. 동의하면 동의 이력이 함께 남고,
        동의하지 않아도 가입되지만 주간 보고는 동의 후에만 할 수 있습니다.

        인증 불필요. **필수: email, password(영문자 · 숫자 포함 8~20자, 특수문자 선택), nickname(2~10자), termsAgreed · privacyAgreed ·
        ageOver19Confirmed(셋 다 true).** 성명은 받지 않습니다.

        호출 예: `POST /api/v1/auth/signup`
        `{"email":"user@example.com","password":"P@ssw0rd!","nickname":"재채기탐정","termsAgreed":true,"privacyAgreed":true,\
        "ageOver19Confirmed":true,"sensitiveHealthInfoAgreed":false}`""")
    @PostMapping("/signup")
    public ResponseEntity<Response<Void>> generalSignup(@Valid @RequestBody AuthGeneralSignupRequest request) {
        authWebUseCase.generalSignup(toCommand(request));
        return ResponseEntity.ok(Response.success());
    }

    @Operation(summary = "이메일 로그인", description = """
        이메일과 비밀번호로 로그인합니다. access token 은 응답 본문으로, refresh 토큰은 `Set-Cookie: refreshToken=...` 으로 내려갑니다
        (HttpOnly · Secure · SameSite=Strict · Path=/api/v1/auth · Max-Age=refresh 만료). 기기 이름은 User-Agent 를 줄여(예: `iPhone · Safari`)
        로그인 기기 목록에 남기고, User-Agent 원문 · IP 는 저장하지 않습니다.

        미가입 이메일 · 비밀번호 불일치 · 비밀번호가 없는 소셜 계정은 모두 AUTH_011(401) 입니다. 같은 이메일로 정해진 횟수(기본 5회) 틀리면 잠기고
        AUTH_012(429, 기본 10분), 한 IP 에서 실패가 많으면(기본 1시간 30회) AUTH_013(429) 입니다. 비밀번호가 맞을 때만 탈퇴 MEMBER_002(403) ·
        정지 MEMBER_003(403) 을 알려 줍니다. 세션 저장소 장애는 AUTH_017(503).

        응답의 `pendingConsents` 가 비어 있지 않으면 약관 개정 등으로 다시 동의해야 하는 필수 항목입니다 — 로그인은 됐으니 재동의 화면으로 이끕니다.
        `reportWritable` 이 false 면 주간 보고 전에 건강정보 동의가 필요합니다.

        인증 불필요. **필수: email, password.** 비밀번호는 가입 규칙을 검사하지 않고 100자 상한만 봅니다.

        호출 예: `POST /api/v1/auth/login` `{"email":"user@example.com","password":"P@ssw0rd!"}`""")
    @PostMapping("/login")
    public ResponseEntity<Response<AuthTokenResponse>> generalLogin(@Valid @RequestBody AuthGeneralLoginRequest request, HttpServletRequest httpServletRequest) {
        AuthCookieResult<AuthTokenResponse> result = authWebUseCase.generalLogin(AuthGeneralLoginCommand.builder()
            .email(request.email())
            .password(request.password())
            .clientIp(clientIpResolver.resolve(httpServletRequest))
            .deviceLabel(deviceLabelResolver.resolve(httpServletRequest.getHeader(HttpHeaders.USER_AGENT)))
            .build());
        return withRefreshCookie(result);
    }

    @Operation(summary = "토큰 재발급", description = """
        refresh 쿠키로 access token 을 다시 받고 refresh 토큰을 회전합니다. 새 refresh 는 `Set-Cookie` 로 내려가고 이전 refresh 는 더 쓸 수 없습니다.
        동의 상태를 다시 읽으므로 `pendingConsents` · `reportWritable` 이 로그인 때와 달라질 수 있습니다.

        쿠키가 없거나 만료 · 세션 만료면 AUTH_014(401), 서명 · 형식 오류면 AUTH_015(401) — 둘 다 다시 로그인합니다. 이미 회전된 refresh 를 다시 쓰면
        탈취로 보고 그 기기 세션을 폐기하고 AUTH_015 입니다. 여러 탭이 같은 쿠키로 동시에 재발급하면 진 쪽은 AUTH_016(409)이고 세션은 그대로라
        **한 번 다시 시도하면** 브라우저가 이긴 쪽의 새 쿠키를 보냅니다. 탈퇴 · 정지 회원은 모든 기기 세션을 지우고 MEMBER_002 · 003(403).
        세션 저장소 장애는 AUTH_017(503).

        인증 불필요. **Authorization 헤더를 싣지 마세요** — 게이트웨이와 이 서비스의 필터는 경로와 무관하게 헤더가 있으면 access 를 검사하므로,
        만료된 access 를 실으면 refresh 가 멀쩡해도 SECURITY_002(401)로 끝납니다. **필수: refreshToken 쿠키** — 로그인 응답의 Set-Cookie 로
        심어지고 브라우저가 `credentials: 'include'` 요청에 자동으로 싣습니다. FE 로컬(localhost)에서는 교차 사이트라 쿠키가 실리지 않습니다.

        호출 예: `POST /api/v1/auth/token/reissue` (바디 없음, 쿠키 자동 전송)""")
    @PostMapping("/token/reissue")
    public ResponseEntity<Response<AuthTokenResponse>> reissueToken(
        @Parameter(description = "[필수] refresh 토큰 쿠키. 브라우저가 자동으로 보내므로 직접 넣지 않습니다. 없으면 AUTH_014", in = ParameterIn.COOKIE)
        @CookieValue(name = RefreshCookieProvider.REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        return withRefreshCookie(authWebUseCase.reissueToken(refreshToken));
    }

    @Operation(summary = "로그아웃", description = """
        지금 기기의 세션만 로그아웃합니다 — 이 기기의 refresh 세션을 지우고 요청한 access token 을 폐기(블랙리스트)하고, refresh 쿠키를 지웁니다
        (`Set-Cookie: refreshToken=; Max-Age=0`). 다른 기기의 로그인은 유지됩니다. 세션 저장소 장애가 있어도 쿠키를 지우고 200 으로 끝냅니다.

        **인증 필요: Authorization 헤더.** 바디 · 쿠키는 필요 없습니다. 토큰이 없으면 SECURITY_001(401), 만료됐으면 SECURITY_002(401) —
        먼저 재발급하고 부릅니다. 재발급도 AUTH_014 · AUTH_015 면 세션이 이미 끊긴 것이라 화면만 로그아웃 상태로 바꾸면 됩니다.

        호출 예: `POST /api/v1/auth/logout` (바디 없음)""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<Void>> logout(@AuthenticationPrincipal MemberLoginActive loginActive) {
        authWebUseCase.logout(loginActive.memberId(), loginActive.sessionId(), loginActive.tokenId(), loginActive.expiresAt());
        return withClearedRefreshCookie();
    }

    @Operation(summary = "로그인 기기 목록", description = """
        로그인된 기기(살아 있는 세션) 목록을 마지막 사용 순으로 돌려줍니다. 지금 요청한 기기는 `current=true` 입니다. 시각은 ISO-8601 UTC 입니다.
        회원당 기기는 최대 5대(설정)이고, 넘으면 마지막 사용이 가장 오래된 기기부터 로그아웃됩니다.

        **인증 필요: Authorization 헤더.** 파라미터는 없습니다.

        호출 예: `GET /api/v1/auth/sessions`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<AuthSessionsResponse>> getSessions(@AuthenticationPrincipal MemberLoginActive loginActive) {
        return ResponseEntity.ok(Response.success(authWebUseCase.getSessions(loginActive.memberId(), loginActive.sessionId())));
    }

    @Operation(summary = "기기 로그아웃", description = """
        지정한 기기의 세션을 지우고, 그 기기가 받은 access token 도 바로 폐기합니다. 내 세션만 지울 수 있고, 이미 없는 세션이어도 200 입니다(멱등).
        지금 기기의 세션이면 refresh 쿠키도 지웁니다.

        **인증 필요: Authorization 헤더. 필수: sessionId(경로, UUID).** 형식이 틀리면 AUTH_114(400).

        호출 예: `DELETE /api/v1/auth/sessions/3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<Void>> revokeSession(
        @AuthenticationPrincipal MemberLoginActive loginActive,
        @Parameter(description = "[필수] 세션 아이디(UUID). 기기 목록의 sessionId 를 그대로 씁니다", required = true, example = "3f2a9c11-0e4b-4a1f-9c3d-0b8e2f7a5d61")
        @PathVariable @Pattern(regexp = AuthValidationMessage.SESSION_ID_REGEXP, message = AuthValidationMessage.SESSION_ID_FORMAT_INVALID) String sessionId) {
        authWebUseCase.revokeSession(loginActive.memberId(), sessionId);
        if (sessionId.equals(loginActive.sessionId())) {
            return withClearedRefreshCookie();
        }
        return ResponseEntity.ok(Response.success());
    }

    @Operation(summary = "다른 기기에서 모두 로그아웃", description = """
        지금 기기를 뺀 모든 기기의 세션을 지우고, 그 기기들이 받은 access token 도 바로 폐기합니다. 지금 기기는 로그인 상태로 남습니다.
        세션을 알 수 없는 토큰이면 AUTH_014(401) — 다시 로그인하면 됩니다.

        **인증 필요: Authorization 헤더.** 파라미터는 없습니다.

        호출 예: `DELETE /api/v1/auth/sessions`""",
        security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Response<Void>> revokeOtherSessions(@AuthenticationPrincipal MemberLoginActive loginActive) {
        authWebUseCase.revokeOtherSessions(loginActive.memberId(), loginActive.sessionId());
        return ResponseEntity.ok(Response.success());
    }

    private ResponseEntity<Response<AuthTokenResponse>> withRefreshCookie(AuthCookieResult<AuthTokenResponse> result) {
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookieProvider.create(result.refreshToken()).toString())
            .body(Response.success(result.response()));
    }

    private ResponseEntity<Response<Void>> withClearedRefreshCookie() {
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookieProvider.clear().toString())
            .body(Response.success());
    }

    /** web DTO → application 명령 변환은 adapter 가 맡는다 — application 이 adapter 타입을 모르게 한다 (architecture-guide §3). 정규화는 Facade. */
    private static AuthGeneralSignupCommand toCommand(AuthGeneralSignupRequest request) {
        return AuthGeneralSignupCommand.builder()
            .email(request.email())
            .password(request.password())
            .nickname(request.nickname())
            .termsAgreed(request.termsAgreed())
            .privacyAgreed(request.privacyAgreed())
            .ageOver19Confirmed(request.ageOver19Confirmed())
            .sensitiveHealthInfoAgreed(request.sensitiveHealthInfoAgreed())
            .build();
    }
}
