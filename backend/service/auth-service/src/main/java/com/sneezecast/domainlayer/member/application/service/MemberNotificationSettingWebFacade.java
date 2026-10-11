package com.sneezecast.domainlayer.member.application.service;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberNotificationSettingResponse;
import com.sneezecast.domainlayer.member.application.command.MemberNotificationSettingChangeCommand;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.info.MemberNotificationSettingInfo;
import com.sneezecast.domainlayer.member.application.port.in.MemberNotificationSettingWebUseCase;
import com.sneezecast.domainlayer.member.application.service.presenter.MemberPresenter;
import com.sneezecast.domainlayer.member.application.service.processor.MemberNotificationSettingProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 알림 설정 조회 · 변경 오케스트레이션. <b>이 Facade 에는 트랜잭션을 걸지 않는다</b> — 동시 첫 변경이 unique 에 막히면 그 트랜잭션이 롤백된 뒤 새
 * 트랜잭션에서 한 번 다시 해야 해서, 트랜잭션은 {@link MemberNotificationSettingProcessor} 의 메서드마다 건다.
 */
@Service
@RequiredArgsConstructor
public class MemberNotificationSettingWebFacade implements MemberNotificationSettingWebUseCase {

    private final MemberNotificationSettingProcessor memberNotificationSettingProcessor;
    private final MemberPresenter memberPresenter;

    @Override
    public MemberNotificationSettingResponse getMyNotificationSettings(long memberId) {
        return memberPresenter.toNotificationSettingResponse(memberNotificationSettingProcessor.get(memberId));
    }

    /**
     * 변경(DB 트랜잭션). 행이 있으면 보낸 항목만 DB 에서 바꾸므로 동시 갱신끼리는 다시 할 일이 없다. 다시 하는 것은 {@code MEMBER_013} 일 때뿐이다 —
     * 같은 회원의 첫 변경 두 건이 겹쳐 unique 에 막혔거나, 조회한 행이 갱신 전에 사라졌을 때. 그 트랜잭션은 이미 롤백됐고, 새 트랜잭션에서 <b>한 번만</b>
     * 다시 한다(행이 보이면 부분 갱신, 없으면 삽입, 회원이 탈퇴했으면 회원 상태 오류). 다시 해도 겹치면 409 를 그대로 낸다.
     */
    @Override
    public MemberNotificationSettingResponse changeMyNotificationSettings(long memberId, MemberNotificationSettingChangeCommand command) {
        MemberNotificationSettingInfo changed;
        try {
            changed = memberNotificationSettingProcessor.change(memberId, command);
        } catch (MemberException exception) {
            if (exception.getErrorCode() != MemberErrorCode.NOTIFICATION_SETTING_SAVE_CONFLICT) {
                throw exception;
            }
            changed = memberNotificationSettingProcessor.change(memberId, command);
        }
        return memberPresenter.toNotificationSettingResponse(changed);
    }
}
