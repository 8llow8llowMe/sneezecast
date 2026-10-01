package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.port.out.MemberConsentRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import com.sneezecast.global.properties.LegalDocumentProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 동의 이력을 남기는 단일 지점. 어느 항목에 어느 문서 버전을 남기는지를 한곳에서 정한다 — 가입 경로(이메일 · 소셜)마다 복제하면 항목이
 * 하나 늘 때 경로마다 남는 행이 조용히 달라진다.
 *
 * <p><b>트랜잭션을 스스로 열지 않는다 — 호출자 트랜잭션에 합류한다.</b> 회원 행과 동의 행은 같은 트랜잭션이어야 한다(하나만 남으면
 * "동의 없는 회원" 이나 "회원 없는 동의" 가 된다).
 */
@Service
@RequiredArgsConstructor
public class MemberConsentProcessor {

    private final MemberConsentRepositoryPort memberConsentRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final LegalDocumentProperties legalDocumentProperties;

    /**
     * 가입 때 받은 동의 · 확인을 <b>같은 시각</b>으로 한 번에 남긴다. 항목별 시각이 갈리면 "한 화면에서 함께 동의했다" 는 사실이 이력에서 사라진다.
     *
     * <p>필수 항목(이용약관 · 개인정보 · 만 19세 이상)은 여기서 묻지 않는다 — 호출자가 이미 확인했다는 전제다. 건강정보 동의는 선택이라
     * 동의한 경우에만 행을 남긴다. 동의하지 않은 항목에 행을 남기지 않는 이유는, "현재 유효한 동의" 가 행의 존재로 판정되기 때문이다.
     *
     * @param sensitiveHealthInfoAgreed 민감정보(건강정보) 별도 동의 여부
     */
    public void recordSignupConsents(long memberId, boolean sensitiveHealthInfoAgreed) {
        LocalDateTime agreedAt = LocalDateTime.now();
        List<MemberConsent> consents = new ArrayList<>(4);
        consents.add(consent(memberId, ConsentType.TERMS_OF_SERVICE, agreedAt));
        consents.add(consent(memberId, ConsentType.PRIVACY_POLICY, agreedAt));
        consents.add(consent(memberId, ConsentType.AGE_OVER_19, agreedAt));
        if (sensitiveHealthInfoAgreed) {
            consents.add(consent(memberId, ConsentType.SENSITIVE_HEALTH_INFO, agreedAt));
        }
        memberConsentRepositoryPort.saveAll(consents);
    }

    /**
     * 항목별 근거 문서 버전. AGE_OVER_19 는 성인 기준을 정한 이용약관 버전을 남긴다 (entity-design §1-3) — 다툼이 생겼을 때 복원해야
     * 하는 것은 "그때 그 조항이 어떤 문장이었는가" 다.
     */
    String documentVersionOf(ConsentType type) {
        return switch (type) {
            case TERMS_OF_SERVICE, AGE_OVER_19 -> legalDocumentProperties.termsVersion();
            case PRIVACY_POLICY -> legalDocumentProperties.privacyVersion();
            case SENSITIVE_HEALTH_INFO -> legalDocumentProperties.sensitiveHealthInfoVersion();
        };
    }

    private MemberConsent consent(long memberId, ConsentType type, LocalDateTime agreedAt) {
        return MemberConsent.builder()
            .id(snowflakeIdGenerator.generateId())
            .memberId(memberId)
            .type(type)
            .documentVersion(documentVersionOf(type))
            .agreedAt(agreedAt)
            .withdrawnAt(null)
            .build();
    }
}
