package com.sneezecast.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 가입 · 동의 때 이력에 남기는 법적 문서의 현재 버전 ({@code member_consent.document_version}).
 *
 * <p><b>값의 정본은 프론트 legal 상수다.</b> 백엔드는 본문을 갖고 있지 않으므로 이 값이 어긋나면 "회원이 무엇에 동의했는가"가
 * 틀어진다. 문서를 개정할 때는 프론트 상수와 이 설정을 같은 배포에 올린다. 이전 버전 동의자는 "유효 동의 없음" 이 된다
 * (entity-design §1-2).
 *
 * <p>비었거나 컬럼 길이({@value #MAX_VERSION_LENGTH}자)를 넘으면 기동에서 실패시킨다 — 가입 요청마다 DB 오류로 드러나는 것보다 낫다.
 *
 * @param termsVersion               이용약관 버전. 만 19세 이상 확인(AGE_OVER_19)도 성인 기준을 정한 이 문서의 버전을 남긴다
 * @param privacyVersion             개인정보 처리방침 버전
 * @param sensitiveHealthInfoVersion 민감정보(건강정보) 수집 · 이용 동의서 버전
 */
@ConfigurationProperties(prefix = "legal")
public record LegalDocumentProperties(
    String termsVersion,
    String privacyVersion,
    String sensitiveHealthInfoVersion
) {

    static final int MAX_VERSION_LENGTH = 20;

    public LegalDocumentProperties {
        requireVersion("legal.terms-version", "LEGAL_TERMS_VERSION", termsVersion);
        requireVersion("legal.privacy-version", "LEGAL_PRIVACY_VERSION", privacyVersion);
        requireVersion("legal.sensitive-health-info-version", "LEGAL_SENSITIVE_HEALTH_INFO_VERSION", sensitiveHealthInfoVersion);
    }

    private static void requireVersion(String key, String envName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " (" + envName + ") 가 비어 있습니다.");
        }
        if (value.length() > MAX_VERSION_LENGTH) {
            throw new IllegalStateException(key + " (" + envName + ") 는 " + MAX_VERSION_LENGTH + "자 이하여야 합니다.");
        }
    }
}
