package com.sneezecast.domainlayer.member.adapter.out.persistence;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberNotificationSettingEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberNotificationSettingRepository;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.mapper.MemberNotificationSettingMapper;
import com.sneezecast.domainlayer.member.application.port.out.MemberNotificationSettingRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.MemberNotificationSetting;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MemberNotificationSettingRepositoryAdapter implements MemberNotificationSettingRepositoryPort {

    private final MemberNotificationSettingRepository memberNotificationSettingRepository;
    private final MemberNotificationSettingMapper memberNotificationSettingMapper;

    @Override
    public Optional<MemberNotificationSetting> findByMemberId(long memberId) {
        return memberNotificationSettingRepository.findByMemberId(memberId).map(memberNotificationSettingMapper::toDomainFromEntity);
    }

    /**
     * {@code MemberRegionRepositoryAdapter#insert} 와 같은 규칙이다 — {@code saveAndFlush} 로 INSERT 를 바로 내보내 unique 위반이 이 자리에서
     * 드러나게 하고(커밋 때 터지면 트랜잭션 프록시 밖의 500), {@code uk_member_notification_setting_member_id} 위반만 도메인 예외로 바꾼다. 다른 제약
     * 위반(PK · NOT NULL 등)을 동시 저장으로 오인시키지 않는다.
     */
    @Override
    public MemberNotificationSetting insert(MemberNotificationSetting setting) {
        try {
            MemberNotificationSettingEntity saved = memberNotificationSettingRepository.saveAndFlush(memberNotificationSettingMapper.toEntityFromDomain(setting));
            return memberNotificationSettingMapper.toDomainFromEntity(saved);
        } catch (DataIntegrityViolationException exception) {
            if (isMemberIdUniqueViolation(exception)) {
                throw new MemberException(MemberErrorCode.NOTIFICATION_SETTING_SAVE_CONFLICT, exception);
            }
            throw exception;
        }
    }

    /**
     * 보낸 항목만 부분 갱신 쿼리로 바꾼다(엔티티 변경 감지를 쓰지 않는다 — 두 칸을 모두 써서 동시 요청의 다른 항목을 되돌린다). 트랜잭션 밖에서 부르면
     * 벌크 갱신이 실패하고, 같은 트랜잭션에서 다시 읽어 응답을 만들어야 하므로 {@code MANDATORY} 로 막는다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int changeByMemberId(long memberId, Boolean weeklyReport, Boolean regionNotice, LocalDateTime changedAt) {
        return memberNotificationSettingRepository.changeByMemberId(memberId, weeklyReport, regionNotice, changedAt);
    }

    /** 탈퇴 정리는 회원 행 처리와 한 트랜잭션이어야 해서 {@code MANDATORY} 다 — 혼자 커밋되지 않는다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteByMemberId(long memberId) {
        return memberNotificationSettingRepository.deleteByMemberId(memberId);
    }

    /** MySQL 은 {@code for key 'member_notification_setting.uk_...'}, H2 는 {@code PUBLIC.UK_..._INDEX_n ...} 로 제약 이름을 싣는다. */
    private boolean isMemberIdUniqueViolation(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(MemberNotificationSettingEntity.MEMBER_ID_UNIQUE_INDEX);
    }
}
