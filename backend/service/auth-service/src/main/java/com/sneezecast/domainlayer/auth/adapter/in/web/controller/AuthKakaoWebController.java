package com.sneezecast.domainlayer.auth.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthKakaoLoginRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthKakaoSignupRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthAuthorizeResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthOAuthLoginResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.response.AuthTokenResponse;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.ClientIpResolver;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.DeviceLabelResolver;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.OAuthCookieProvider;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.RefreshCookieProvider;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthLoginCommand;
import com.sneezecast.domainlayer.auth.application.command.AuthOAuthSignupCommand;
import com.sneezecast.domainlayer.auth.application.info.AuthCookieResult;
import com.sneezecast.domainlayer.auth.application.info.AuthOAuthCookieResult;
import com.sneezecast.domainlayer.auth.application.port.in.AuthOAuthWebUseCase;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카카오 로그인. 카카오 redirect_uri 는 <b>프론트 콜백 페이지</b>이고, 그 페이지가 받은 code · state 를 같은 사이트 fetch 로 이 API 에 넘긴다.
 *
 * <p>일회용 쿠키(state · 가입표 · 연결 확인표)는 그 값을 쓰는 요청에서 지운다. 지우는 {@code Set-Cookie} 는 유스케이스를 부르기 <b>전에</b>
 * {@link HttpServletResponse} 에 직접 넣는다 — 그래야 뒤 단계가 실패해 예외 처리기가 응답을 만들어도 쿠키가 지워진다(값은 서버에서 이미 소비됐거나 쓸 수
 * 없다). 요청 검증에 막힌 요청은 메서드 본문까지 오지 않아 쿠키를 건드리지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/kakao")
@Tag(name = "카카오 로그인", description = "카카오 인가 주소, 카카오 로그인 · 가입 · 기존 이메일 계정 연결 API")
public class AuthKakaoWebController {

    private final AuthOAuthWebUseCase authOAuthWebUseCase;
    private final ClientIpResolver clientIpResolver;
    private final DeviceLabelResolver deviceLabelResolver;
    private final RefreshCookieProvider refreshCookieProvider;
    private final OAuthCookieProvider oAuthCookieProvider;

    @Operation(summary = "카카오 인가 주소", description = """
        카카오 동의 화면 주소를 돌려줍니다. 화면은 받은 `authorizeUrl` 로 이동합니다. 동의 항목은 이메일 · 닉네임뿐입니다(프로필 이미지는 받지 않습니다).
        주소에 실린 일회용 state 를 `Set-Cookie: oauthState=...` 로도 내립니다(HttpOnly · Secure · SameSite=Strict · Path=/api/v1/auth ·
        Max-Age=state 수명 기본 10분) — 카카오 로그인 요청이 이 브라우저에서 시작됐는지 확인하는 데 씁니다. `fetch` 에 `credentials: 'include'` 가 필요합니다.

        `switchAccount=true` 면 카카오에 로그인된 계정을 그대로 쓰지 않고 계정을 고르게 합니다("다른 카카오 계정으로 계속하기").
        새 로그인을 시작하므로 앞서 받다 만 가입표 · 연결 확인표 쿠키는 이 응답에서 지웁니다(공용 기기에 남은 표로 가입 · 연결되지 않게).

        한 IP 에서 정해진 시간(기본 10분) 안에 너무 많이(기본 30회) 부르면 AUTH_028(429) 입니다.

        인증 불필요. 파라미터는 선택입니다.

        호출 예: `GET /api/v1/auth/kakao/authorize?switchAccount=false`""")
    @GetMapping("/authorize")
    public ResponseEntity<Response<AuthOAuthAuthorizeResponse>> authorize(
        @Parameter(description = "[선택] 다른 카카오 계정으로 로그인할지. 기본 false", example = "false") @RequestParam(defaultValue = "false") boolean switchAccount,
        HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        addCookie(httpServletResponse, oAuthCookieProvider.clearSignupTicket());
        addCookie(httpServletResponse, oAuthCookieProvider.clearLinkTicket());
        AuthOAuthCookieResult<AuthOAuthAuthorizeResponse> result = authOAuthWebUseCase.authorize(OAuthProvider.KAKAO, switchAccount,
            clientIpResolver.resolve(httpServletRequest));
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, oAuthCookieProvider.createState(result.cookieValue()).toString())
            .body(Response.success(result.response()));
    }

    @Operation(summary = "카카오 로그인", description = """
        카카오 콜백 페이지가 받은 `code` · `state` 로 로그인합니다. 인가 코드가 접근 로그에 남지 않게 GET 이 아니라 POST 바디로 받습니다.
        state 쿠키는 결과와 무관하게 이 응답에서 지웁니다(일회용). 결과와 맞지 않는 표 쿠키도 지웁니다 — LOGGED_IN 이면 가입표 · 연결 확인표 둘 다,
        SIGNUP_REQUIRED 면 연결 확인표, LINK_REQUIRED 면 가입표.

        결과 `result`:
        - `LOGGED_IN` — 카카오 로그인이 연결된 회원입니다. 이메일 로그인과 같은 응답 필드에 refresh 쿠키(`refreshToken`)가 내려갑니다.
        - `SIGNUP_REQUIRED` — 처음 온 이메일입니다. 가입표가 쿠키(`oauthSignupTicket`, 기본 30분)로 내려가고 `nickname` 을 줍니다.
          화면은 동네 · 성인 확인 · 가입 동의를 받은 뒤 `POST /api/v1/auth/kakao/signup` 을 부릅니다. 동의 전에는 회원을 만들지 않습니다.
        - `LINK_REQUIRED` — 같은 이메일로 가입한 이메일 계정이 있습니다. 연결 확인표가 쿠키(`oauthLinkTicket`, 기본 10분)로 내려가고 가린 `email`
          (예: `d***@example.com`)을 줍니다. 화면은 "카카오 로그인을 연결할까요?" 를 묻고 `POST /api/v1/auth/kakao/link` 를 부릅니다.
          "다른 카카오 계정으로 계속하기" 는 인가 주소를 `switchAccount=true` 로 다시 받으면 됩니다.

        실패(화면은 모두 `/login?error=kakao-fail`): state 쿠키 없음 · 불일치 · 만료 · 재사용 AUTH_020(400), 카카오가 code 를 거부 AUTH_021(400),
        카카오 장애 · 응답 지연 AUTH_022(503), 카카오 이메일 미제공 AUTH_023(400), 미인증 · 유효하지 않은 이메일 AUTH_024(400),
        탈퇴 MEMBER_002 · 정지 MEMBER_003(403), 저장소 장애 AUTH_006 · AUTH_017(503). 검증: code 누락 AUTH_117 · 512자 초과 AUTH_118,
        state 누락 AUTH_119 · 100자 초과 AUTH_120.

        인증 불필요. **필수: 요청 바디의 code, state 와 oauthState 쿠키(브라우저가 자동으로 보냄).**

        호출 예: `POST /api/v1/auth/kakao/login` `{"code":"x8Kp0bQ2...","state":"q3J9x0b2..."}`""")
    @PostMapping("/login")
    public ResponseEntity<Response<AuthOAuthLoginResponse>> login(@Valid @RequestBody AuthKakaoLoginRequest request,
        @Parameter(description = "[필수] 인가 주소 응답이 심은 state 쿠키. 브라우저가 자동으로 보낸다", in = ParameterIn.COOKIE)
        @CookieValue(name = OAuthCookieProvider.OAUTH_STATE_COOKIE, required = false) String cookieState,
        HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        addCookie(httpServletResponse, oAuthCookieProvider.clearState());
        AuthOAuthCookieResult<AuthOAuthLoginResponse> result = authOAuthWebUseCase.login(AuthOAuthLoginCommand.builder()
            .provider(OAuthProvider.KAKAO)
            .code(request.code())
            .state(request.state())
            .cookieState(cookieState)
            .deviceLabel(deviceLabel(httpServletRequest))
            .build());
        // 결과에 맞는 쿠키를 심고, 그 결과와 맞지 않는 표 쿠키(앞서 받다 만 것)는 지운다 — 남은 표로 바디 없는 연결 · 가입이 통하지 않게.
        List<ResponseCookie> cookies = switch (result.cookie()) {
            case REFRESH_TOKEN -> List.of(refreshCookieProvider.create(result.cookieValue()), oAuthCookieProvider.clearSignupTicket(),
                oAuthCookieProvider.clearLinkTicket());
            case SIGNUP_TICKET -> List.of(oAuthCookieProvider.createSignupTicket(result.cookieValue()), oAuthCookieProvider.clearLinkTicket());
            case LINK_TICKET -> List.of(oAuthCookieProvider.createLinkTicket(result.cookieValue()), oAuthCookieProvider.clearSignupTicket());
            case STATE -> throw new IllegalStateException("login never issues a state cookie");
        };
        cookies.forEach(cookie -> addCookie(httpServletResponse, cookie));
        return ResponseEntity.ok().body(Response.success(result.response()));
    }

    @Operation(summary = "카카오 가입", description = """
        카카오 로그인이 `SIGNUP_REQUIRED` 였을 때, 가입 동의를 받은 뒤 가입하고 **바로 로그인합니다**(카카오 회원은 비밀번호가 없어 따로 로그인할 수 없습니다).
        응답은 이메일 로그인 응답과 같고 refresh 쿠키가 내려갑니다. 이메일 · 닉네임은 가입표에 든 카카오 값을 씁니다. 가입표 쿠키는 이 응답에서 지웁니다.

        필수 체크 셋은 각각 다른 코드로 막힙니다 — 이용약관 AUTH_110, 개인정보 수집 · 이용 AUTH_111, 만 19세 이상 확인 AUTH_112(이때는 가입표를
        건드리지 않아 고쳐서 다시 보내면 됩니다). 건강정보(민감정보) 동의는 이메일 가입과 같이 가입 뒤 별도 API 로 받습니다.
        가입표가 없거나 만료 · 이미 썼으면 AUTH_025(400) — 화면은 카카오 로그인부터 다시 합니다. 그사이 같은 이메일로 가입됐으면 MEMBER_001(409).

        인증 불필요. **필수: termsAgreed · privacyAgreed · ageOver19Confirmed(셋 다 true) 와 oauthSignupTicket 쿠키(브라우저가 자동으로 보냄).**

        호출 예: `POST /api/v1/auth/kakao/signup` `{"termsAgreed":true,"privacyAgreed":true,"ageOver19Confirmed":true}`""")
    @PostMapping("/signup")
    public ResponseEntity<Response<AuthTokenResponse>> signup(@Valid @RequestBody AuthKakaoSignupRequest request,
        @Parameter(description = "[필수] 카카오 로그인 응답이 심은 가입표 쿠키. 브라우저가 자동으로 보낸다", in = ParameterIn.COOKIE)
        @CookieValue(name = OAuthCookieProvider.OAUTH_SIGNUP_TICKET_COOKIE, required = false) String signupTicket,
        HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        addCookie(httpServletResponse, oAuthCookieProvider.clearSignupTicket());
        return withRefreshCookie(authOAuthWebUseCase.signup(AuthOAuthSignupCommand.builder()
            .signupTicket(signupTicket)
            .termsAgreed(request.termsAgreed())
            .privacyAgreed(request.privacyAgreed())
            .ageOver19Confirmed(request.ageOver19Confirmed())
            .deviceLabel(deviceLabel(httpServletRequest))
            .build()));
    }

    @Operation(summary = "기존 이메일 계정에 카카오 로그인 연결", description = """
        카카오 로그인이 `LINK_REQUIRED` 였을 때, 사용자가 연결을 확인하면 그 이메일 계정에 카카오 로그인을 연결하고 로그인합니다. 비밀번호는 그대로라
        이메일 로그인도 계속 됩니다. 연결 사실은 그 이메일로 안내 메일이 갑니다. 응답은 이메일 로그인 응답과 같고 refresh 쿠키가 내려갑니다.
        연결 확인표 쿠키는 이 응답에서 지웁니다.

        확인표가 없거나 만료 · 이미 썼으면 AUTH_026(400) — 화면은 카카오 로그인부터 다시 합니다. 그사이 계정이 탈퇴 · 정지됐거나 이미 연결됐으면 AUTH_027(409).

        인증 불필요. 바디는 없습니다. **필수: oauthLinkTicket 쿠키(브라우저가 자동으로 보냄).**

        호출 예: `POST /api/v1/auth/kakao/link` (바디 없음)""")
    @PostMapping("/link")
    public ResponseEntity<Response<AuthTokenResponse>> link(
        @Parameter(description = "[필수] 카카오 로그인 응답이 심은 연결 확인표 쿠키. 브라우저가 자동으로 보낸다", in = ParameterIn.COOKIE)
        @CookieValue(name = OAuthCookieProvider.OAUTH_LINK_TICKET_COOKIE, required = false) String linkTicket,
        HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        addCookie(httpServletResponse, oAuthCookieProvider.clearLinkTicket());
        return withRefreshCookie(authOAuthWebUseCase.link(linkTicket, deviceLabel(httpServletRequest)));
    }

    private ResponseEntity<Response<AuthTokenResponse>> withRefreshCookie(AuthCookieResult<AuthTokenResponse> result) {
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookieProvider.create(result.refreshToken()).toString())
            .body(Response.success(result.response()));
    }

    private String deviceLabel(HttpServletRequest request) {
        return deviceLabelResolver.resolve(request.getHeader(HttpHeaders.USER_AGENT));
    }

    private static void addCookie(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
