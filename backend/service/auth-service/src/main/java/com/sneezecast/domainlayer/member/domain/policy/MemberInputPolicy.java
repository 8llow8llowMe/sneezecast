package com.sneezecast.domainlayer.member.domain.policy;

/**
 * 회원 입력 규칙의 단일 기준점 — 가입(auth) · 내 정보 수정 · 비밀번호 변경 · 재설정 · 카카오 가입 닉네임 정규화가 함께 쓴다. 규칙의 주인은 회원(member)이고, auth 의 요청
 * 검증은 이 값을 참조한다(auth → member 방향만 둔다).
 *
 * <p>시안 Signup-account 와 같다 — 비밀번호 8~20자 · 영문자와 숫자 필수 · 공백 금지 · 특수문자 선택(상한 20자는 BCrypt 72바이트 한도 안), 닉네임은
 * 앞뒤 공백을 지운 뒤 2~10자.
 */
public final class MemberInputPolicy {

    /**
     * 비밀번호 문자 구성 규칙 — 영문자 · 숫자 필수, 특수문자는 써도 되지만 요구하지 않는다. 길이는 @Size 가 맡으므로 여기서는 구성만 본다 (같은 의미를 두
     * 제약으로 중복 검사하지 않는다).
     */
    public static final String PASSWORD_REGEXP = "^(?=.*[A-Za-z])(?=.*\\d)\\S+$";

    /**
     * 닉네임 길이 — 앞뒤 공백을 지운 값 기준이다(저장값과 같은 기준). 길이는 <b>코드포인트</b> 기준이다(이모지도 한 글자) — 요청 검증({@code @StrippedSize})과
     * 카카오 닉네임 정규화가 같은 기준으로 잰다.
     */
    public static final int NICKNAME_MIN_LENGTH = 2;
    public static final int NICKNAME_MAX_LENGTH = 10;

    private MemberInputPolicy() {
    }
}
