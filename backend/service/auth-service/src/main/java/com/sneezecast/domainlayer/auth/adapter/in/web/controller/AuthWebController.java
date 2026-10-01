package com.sneezecast.domainlayer.auth.adapter.in.web.controller;

import com.sneezecast.common.dto.Response;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthEmailCodeSendRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthEmailCodeVerifyRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.dto.request.AuthGeneralSignupRequest;
import com.sneezecast.domainlayer.auth.adapter.in.web.support.ClientIpResolver;
import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.port.in.AuthWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "이메일 인증, 회원가입 API")
public class AuthWebController {

    private final AuthWebUseCase authWebUseCase;
    private final ClientIpResolver clientIpResolver;

    @Operation(summary = "이메일 인증코드 발송", description = """
        회원가입용 이메일 인증코드(대문자 · 숫자 8자)를 메일로 보냅니다.
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
        IP당 검증 상한을 넘으면 AUTH_010(429) 입니다. 어느 응답도 가입 여부를 뜻하지 않습니다. 대소문자 · 앞뒤 공백은 구분하지 않습니다.

        인증 불필요. **필수: 요청 바디의 email, code.**

        호출 예: `POST /api/v1/auth/email/verify-code` `{"email":"user@example.com","code":"A3K7MP2X"}`""")
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

        인증 불필요. **필수: email, password(영문자 · 숫자 · 특수문자 포함 8~20자), nickname(10자 이하), termsAgreed · privacyAgreed ·
        ageOver19Confirmed(셋 다 true).** 성명은 받지 않습니다.

        호출 예: `POST /api/v1/auth/signup`
        `{"email":"user@example.com","password":"P@ssw0rd!","nickname":"재채기탐정","termsAgreed":true,"privacyAgreed":true,\
        "ageOver19Confirmed":true,"sensitiveHealthInfoAgreed":false}`""")
    @PostMapping("/signup")
    public ResponseEntity<Response<Void>> generalSignup(@Valid @RequestBody AuthGeneralSignupRequest request) {
        authWebUseCase.generalSignup(toCommand(request));
        return ResponseEntity.ok(Response.success());
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
