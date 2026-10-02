package com.sneezecast.domainlayer.member.adapter.out.persistence;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.mapper.MemberMapper;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.Member;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MemberRepositoryAdapter implements MemberRepositoryPort {

    private final MemberRepository memberRepository;
    private final MemberMapper memberMapper;

    /**
     * {@code saveAndFlush} 로 INSERT 를 바로 내보내 unique 위반이 이 자리에서 드러나게 한다. 커밋 시점까지 미루면 예외가 트랜잭션
     * 프록시 밖에서 {@code DataIntegrityViolationException} 그대로 터져 500 이 된다.
     *
     * <p>{@code uk_member_email} 위반만 도메인 예외로 바꾼다. 다른 제약 위반(NOT NULL 등)을 중복 가입으로 오인시키지 않는다.
     */
    @Override
    public Member save(Member member) {
        try {
            MemberEntity saved = memberRepository.saveAndFlush(memberMapper.toEntityFromDomain(member));
            return memberMapper.toDomainFromEntity(saved);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueViolation(exception)) {
                throw new MemberException(MemberErrorCode.EXIST_MEMBER_EMAIL);
            }
            throw exception;
        }
    }

    @Override
    public boolean existsByEmail(String email) {
        return memberRepository.existsByEmail(email);
    }

    @Override
    public Optional<Member> findByEmail(String email) {
        return memberRepository.findByEmail(email).map(memberMapper::toDomainFromEntity);
    }

    @Override
    public Optional<Member> findById(long memberId) {
        return memberRepository.findById(memberId).map(memberMapper::toDomainFromEntity);
    }

    /**
     * 조회한 엔티티를 바꿔 변경 감지로 UPDATE 한다. 도메인에서 새로 매핑한 엔티티를 {@code save} 하면 {@code isNew()} 가 true 라 INSERT 로 가서 PK
     * 위반이다(MemberEntity 주의). 트랜잭션이 없으면 조회한 엔티티가 바로 분리돼 변경이 조용히 사라지므로 {@code MANDATORY} 로 호출자 트랜잭션을 강제한다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Member> updateNickname(long memberId, String nickname) {
        return memberRepository.findById(memberId).map(entity -> {
            entity.changeNickname(nickname);
            return memberMapper.toDomainFromEntity(entity);
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Member> updatePassword(long memberId, String encodedPassword) {
        return memberRepository.findById(memberId).map(entity -> {
            entity.changePassword(encodedPassword);
            return memberMapper.toDomainFromEntity(entity);
        });
    }

    /** MySQL 은 {@code for key 'member.uk_member_email'}, H2 는 {@code PUBLIC.UK_MEMBER_EMAIL ...} 로 싣는다 — 대소문자를 무시하고 본다. */
    private boolean isEmailUniqueViolation(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(MemberEntity.EMAIL_UNIQUE_INDEX);
    }
}
