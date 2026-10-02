package com.sneezecast.domainlayer.auth.application.port.out;

/**
 * 인증 메일 발송. 구현은 요청 스레드 밖에서 보내고 실패를 호출자에게 돌려주지 않는다 — 사용자는 메일이 오지 않으면 쿨다운 뒤 다시 요청한다.
 */
public interface MailSendPort {

    void sendVerificationCode(String email, String code);

    /** 이미 가입된 이메일로 인증을 요청한 경우. 계정 존재 여부는 응답이 아니라 메일함 소유자에게만 알린다. */
    void sendAlreadyRegisteredNotice(String email);

    /** 비밀번호 재설정 인증코드. 정상(ACTIVE) 회원에게만 보낸다. */
    void sendPasswordResetCode(String email, String code);

    /** 가입된 계정이 없는 이메일로 재설정을 요청한 경우. 가입 여부는 응답이 아니라 메일함 소유자에게만 알린다. */
    void sendPasswordResetNoAccountNotice(String email);

    /**
     * 비밀번호가 없는(카카오로만 로그인하는) 계정의 이메일로 재설정을 요청한 경우 — 코드 대신 "카카오로 로그인해 주세요" 안내를 보낸다. 계정 종류는 응답이
     * 아니라 메일함 소유자에게만 알린다.
     */
    void sendPasswordResetOAuthOnlyNotice(String email);

    /** 이메일 계정에 카카오 로그인이 연결된 사실을 알린다 — 본인이 하지 않았으면 바로 알아챌 수 있게 한다. */
    void sendOAuthLinkedNotice(String email);
}
