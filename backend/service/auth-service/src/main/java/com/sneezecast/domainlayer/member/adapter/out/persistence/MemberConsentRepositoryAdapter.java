package com.sneezecast.domainlayer.member.adapter.out.persistence;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberConsentEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberConsentRepository;
import com.sneezecast.domainlayer.member.application.mapper.MemberConsentMapper;
import com.sneezecast.domainlayer.member.application.port.out.MemberConsentRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MemberConsentRepositoryAdapter implements MemberConsentRepositoryPort {

    private final MemberConsentRepository memberConsentRepository;
    private final MemberConsentMapper memberConsentMapper;

    @Override
    public void saveAll(List<MemberConsent> consents) {
        List<MemberConsentEntity> entities = consents.stream()
            .map(memberConsentMapper::toEntityFromDomain)
            .toList();
        memberConsentRepository.saveAll(entities);
    }

    @Override
    public List<MemberConsent> findAllByMemberId(long memberId) {
        return memberConsentRepository.findAllByMemberId(memberId).stream()
            .map(memberConsentMapper::toDomainFromEntity)
            .toList();
    }

    /** 트랜잭션이 없으면 잠금이 조회 직후 풀려 의미가 없으므로 {@code MANDATORY} 로 호출자 트랜잭션을 강제한다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MemberConsent> findAllByMemberIdAndTypeForUpdate(long memberId, ConsentType type) {
        return memberConsentRepository.findAllForUpdateByMemberIdAndType(memberId, type).stream()
            .map(memberConsentMapper::toDomainFromEntity)
            .toList();
    }

    /**
     * 조회한 엔티티를 바꿔 변경 감지로 UPDATE 한다 — 매핑한 엔티티를 {@code save} 하면 INSERT 로 가서 PK 위반이다(MemberConsentEntity 주의).
     * 트랜잭션이 없으면 변경이 조용히 사라지므로 {@code MANDATORY} 다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void withdraw(long consentId, LocalDateTime withdrawnAt) {
        memberConsentRepository.findById(consentId).ifPresent(entity -> entity.withdraw(withdrawnAt));
    }
}
