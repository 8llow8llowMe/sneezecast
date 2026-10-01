package com.sneezecast.domainlayer.member.adapter.out.persistence;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberConsentEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberConsentRepository;
import com.sneezecast.domainlayer.member.application.mapper.MemberConsentMapper;
import com.sneezecast.domainlayer.member.application.port.out.MemberConsentRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
}
