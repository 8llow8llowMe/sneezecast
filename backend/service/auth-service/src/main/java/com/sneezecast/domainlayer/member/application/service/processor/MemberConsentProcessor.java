package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.port.out.MemberConsentRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import com.sneezecast.global.properties.LegalDocumentProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동의 이력을 남기고 현재 동의 상태를 판정하는 단일 지점. 어느 항목에 어느 문서 버전을 남기는지, 무엇을 "유효한 동의" 로 보는지를 한곳에서
 * 정한다 — 가입 경로(이메일 · 소셜)나 토큰 발급 경로마다 복제하면 항목이 하나 늘 때 경로마다 결과가 조용히 달라진다.
 *
 * <p><b>트랜잭션을 스스로 열지 않는다 — 호출자 트랜잭션에 합류한다.</b> 회원 행과 동의 행은 같은 트랜잭션이어야 한다(하나만 남으면
 * "동의 없는 회원" 이나 "회원 없는 동의" 가 된다). 예외는 {@link #withdraw} 다 — 커밋 뒤에 세션 폐기(Redis)가 이어져 Facade 에 트랜잭션을 걸 수 없다
 * (architecture-guide §3-1).
 */
@Service
@RequiredArgsConstructor
public class MemberConsentProcessor {

    /** 가입 필수 항목. */
    static final List<ConsentType> SIGNUP_REQUIRED_CONSENTS = List.of(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY, ConsentType.AGE_OVER_19);

    /** 문서가 개정되면 다시 받는 필수 항목. 순서는 동의 상태 응답(pendingConsents)의 순서이기도 하다. */
    static final List<ConsentType> RECONSENT_REQUIRED_CONSENTS = List.of(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY);

    /** 가입 뒤 동의 API 로 받는 항목 — 약관 재동의(이용약관 · 개인정보)와 건강정보 동의. 만 19세 확인은 가입 때 한 번만 받는다. */
    static final Set<ConsentType> AGREEABLE_CONSENTS = Set.of(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY, ConsentType.SENSITIVE_HEALTH_INFO);

    /** 같은 항목에서 더 최근 행을 고른다. 동의 시각이 같으면(같은 초에 재동의) Snowflake ID 가 큰 쪽이 나중 행이다. */
    private static final BinaryOperator<MemberConsent> LATEST = BinaryOperator.maxBy(
        Comparator.comparing(MemberConsent::agreedAt).thenComparingLong(MemberConsent::id));

    private final MemberConsentRepositoryPort memberConsentRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final LegalDocumentProperties legalDocumentProperties;
    private final ReportPurgeRequestProcessor reportPurgeRequestProcessor;

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
        SIGNUP_REQUIRED_CONSENTS.forEach(type -> consents.add(consent(memberId, type, agreedAt)));
        if (sensitiveHealthInfoAgreed) {
            consents.add(consent(memberId, ConsentType.SENSITIVE_HEALTH_INFO, agreedAt));
        }
        memberConsentRepositoryPort.saveAll(consents);
    }

    /**
     * 현재 동의 상태. 항목별로 {@code agreed_at} 이 가장 늦은 행이 <b>철회되지 않았고 현재 설정 버전과 같을 때만</b> 유효하다 (entity-design
     * §1-2). 예전 행이 유효해도 최신 행이 철회됐거나 옛 버전이면 무효다 — 문서를 개정하면 설정 버전만 올리고, 이전 버전 동의자는 다음 로그인 ·
     * 재발급 때 재동의 대상으로 드러난다.
     *
     * <p><b>재동의 대기({@code pendingRequiredConsents})는 이용약관 · 개인정보만 본다.</b> 만 19세 확인은 동의가 아니라 사실 확인이라 버전을 보지
     * 않고(2026-10-02 결정 — 이용약관을 개정해도 다시 묻지 않는다) 동의 API 로도 받지 않는다. 대기에 넣으면 화면이 풀 수 없는 항목이 된다.
     *
     * <p>{@code purgePending} 은 미완료 보고 파기 요청이 있는지다 — {@code report:write} 판정(auth 의 {@code ReportScopePolicy})의 입력이다.
     */
    public MemberConsentStatusInfo currentStatus(long memberId) {
        Map<ConsentType, MemberConsent> latestByType = new EnumMap<>(ConsentType.class);
        for (MemberConsent consent : memberConsentRepositoryPort.findAllByMemberId(memberId)) {
            latestByType.merge(consent.type(), consent, LATEST);
        }

        List<ConsentType> pending = RECONSENT_REQUIRED_CONSENTS.stream()
            .filter(type -> !isValid(latestByType.get(type)))
            .toList();
        return MemberConsentStatusInfo.builder()
            .pendingRequiredConsents(pending)
            .healthInfoAgreed(isValid(latestByType.get(ConsentType.SENSITIVE_HEALTH_INFO)))
            .purgePending(reportPurgeRequestProcessor.hasIncompletePurge(memberId))
            .build();
    }

    /**
     * 가입 뒤 동의 — 약관 재동의(이용약관 · 개인정보)와 건강정보 동의. 이미 현재 버전으로 유효하면 아무것도 남기지 않는다(멱등). 유효하지 않으면 새 행을
     * 더하고 기존 행은 고치지 않는다(entity-design §1-2).
     *
     * <p>새 행이 생겨도 지금 access token 의 scope 는 그대로다 — 재발급 때 다시 계산된다. 미완료 보고 파기가 있으면 건강정보에 다시 동의해도
     * {@code report:write} 는 파기가 끝날 때까지 실리지 않는다.
     *
     * @param documentVersion 화면이 보여 준 문서 버전. 서버 현재 버전과 다르면 {@code CONSENT_VERSION_MISMATCH}(409)
     * @throws MemberException 만 19세 확인 등 이 경로로 받지 않는 항목이면 {@code CONSENT_NOT_AGREEABLE}(400)
     */
    public void agree(long memberId, ConsentType type, String documentVersion) {
        if (!AGREEABLE_CONSENTS.contains(type)) {
            throw new MemberException(MemberErrorCode.CONSENT_NOT_AGREEABLE);
        }
        if (!documentVersionOf(type).equals(documentVersion)) {
            throw new MemberException(MemberErrorCode.CONSENT_VERSION_MISMATCH);
        }
        MemberConsent latest = memberConsentRepositoryPort.findAllByMemberId(memberId).stream()
            .filter(consent -> consent.type() == type)
            .reduce(LATEST)
            .orElse(null);
        if (isValid(latest)) {
            return;
        }
        memberConsentRepositoryPort.saveAll(List.of(consent(memberId, type, LocalDateTime.now())));
    }

    /**
     * 건강정보 동의 철회. 한 트랜잭션에서 최신 동의 행에 철회 시각을 채우고 원시 보고 파기 요청을 남긴다 — 둘 중 하나만 남으면 "철회했는데 보고가
     * 남음" 이나 "동의 중인데 보고가 지워짐" 이 된다.
     *
     * <ul>
     *   <li>철회할 행은 <b>최신 행이 철회되지 않은 것</b>이다. 버전은 보지 않는다 — 문서 개정으로 동의가 무효가 된 회원도 옛 버전에 동의해 남긴
     *       보고가 있으니 철회하면 파기해야 한다.</li>
     *   <li>철회할 행이 없으면(한 번도 동의하지 않음 · 이미 철회) 아무것도 남기지 않는다. 동의한 적 없는 회원에게 파기 요청을 만들면 파기 스케줄러(#155)가
     *       돌기 전까지 {@code report:write} 가 영원히 막힌다.</li>
     *   <li>미완료 파기 요청이 이미 있으면(철회 → 재동의 → 다시 철회) 둘째 요청을 만들지 않는다.</li>
     *   <li>행을 쓰기 잠금으로 읽어, 같은 회원의 동시 철회는 차례로 처리된다(뒤 요청은 철회된 행을 보고 아무것도 하지 않는다).</li>
     * </ul>
     *
     * @return 이번에 철회했으면 true
     * @throws MemberException 건강정보가 아닌 항목이면 {@code CONSENT_NOT_WITHDRAWABLE}(400)
     */
    @Transactional
    public boolean withdraw(long memberId, ConsentType type) {
        if (type != ConsentType.SENSITIVE_HEALTH_INFO) {
            throw new MemberException(MemberErrorCode.CONSENT_NOT_WITHDRAWABLE);
        }
        MemberConsent latest = memberConsentRepositoryPort.findAllByMemberIdAndTypeForUpdate(memberId, type).stream()
            .reduce(LATEST)
            .orElse(null);
        if (latest == null || latest.withdrawnAt() != null) {
            return false;
        }
        LocalDateTime withdrawnAt = LocalDateTime.now();
        memberConsentRepositoryPort.withdraw(latest.id(), withdrawnAt);
        reportPurgeRequestProcessor.requestIfAbsent(memberId, PurgeReason.HEALTH_CONSENT_WITHDRAWN, withdrawnAt);
        return true;
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

    /** 최신 행이 철회되지 않았고 현재 버전이면 유효하다. 만 19세 확인은 사실 확인이라 버전을 보지 않는다(2026-10-02 결정). */
    private boolean isValid(MemberConsent latest) {
        if (latest == null || latest.withdrawnAt() != null) {
            return false;
        }
        return latest.type() == ConsentType.AGE_OVER_19 || documentVersionOf(latest.type()).equals(latest.documentVersion());
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
