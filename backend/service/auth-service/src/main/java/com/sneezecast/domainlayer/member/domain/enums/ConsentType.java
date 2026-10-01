package com.sneezecast.domainlayer.member.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 가입 · 이용 중에 받아 이력으로 남기는 동의 · 확인 항목 (entity-design §1-3).
 *
 * <p><b>필수 여부는 이 enum 이 아니라 요청 검증과 가입 로직이 정한다</b> — 항목의 정체와 필수 여부는 개정 주기가 다르다.
 * 지금 가입 필수는 TERMS_OF_SERVICE · PRIVACY_POLICY · AGE_OVER_19 이고, SENSITIVE_HEALTH_INFO 는 가입 때 선택(보고에는 필수)이다.
 *
 * <ul>
 *   <li>{@link #SENSITIVE_HEALTH_INFO} — 개인정보 보호법 제23조 민감정보 <b>별도 동의</b>. 가입 동의와 화면 · 체크를 나누고, 철회할 수 있다.
 *       동의 여부는 JWT scope {@code report:write} 로 전달된다.</li>
 *   <li>{@link #AGE_OVER_19} — 동의가 아니라 <b>사실 확인(자기신고)</b>이라 철회 개념이 없다. 같은 테이블에 두는 이유는 "가입 때 무엇을
 *       묻고 확인받았는가"를 한 자리에서 복원하기 위해서다. {@code document_version} 에는 성인 기준을 정한 이용약관 버전을 남긴다.</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ConsentType implements CodeNameDescribable {
    TERMS_OF_SERVICE("이용약관", "서비스 이용약관 동의. 가입 필수, 탈퇴로만 철회한다"),
    PRIVACY_POLICY("개인정보 수집·이용", "이메일 · 닉네임 · 프로필 이미지 · 행정동 수집 · 이용 동의. 가입 필수, 탈퇴로만 철회한다"),
    SENSITIVE_HEALTH_INFO("민감정보(건강정보) 수집·이용", "주간 증상 보고를 위한 별도 동의. 가입 때는 선택이고 보고에는 필수이며, 철회할 수 있다"),
    AGE_OVER_19("만 19세 이상 확인", "성인 본인 확인(자기신고). 가입 필수이며 철회 대상이 아니다");

    private final String displayName;
    private final String description;
}
