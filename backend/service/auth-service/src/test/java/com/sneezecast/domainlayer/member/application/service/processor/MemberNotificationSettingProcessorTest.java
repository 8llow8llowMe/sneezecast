package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.member.application.command.MemberNotificationSettingChangeCommand;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.info.MemberNotificationSettingInfo;
import com.sneezecast.domainlayer.member.application.port.out.MemberNotificationSettingRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.MemberNotificationSetting;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemberNotificationSettingProcessorTest {

    private static final long MEMBER_ID = 42L;

    private MemberQueryProcessor memberQueryProcessor;
    private MemberNotificationSettingRepositoryPort port;
    private SnowflakeIdGenerator snowflakeIdGenerator;
    private MemberNotificationSettingProcessor processor;

    @BeforeEach
    void setUp() {
        memberQueryProcessor = mock(MemberQueryProcessor.class);
        port = mock(MemberNotificationSettingRepositoryPort.class);
        snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
        processor = new MemberNotificationSettingProcessor(memberQueryProcessor, port, snowflakeIdGenerator);
        when(snowflakeIdGenerator.generateId()).thenReturn(900L);
        when(port.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("한 번도 바꾸지 않았으면 모두 꺼짐이고 행을 만들지 않는다")
    void getWithoutRowIsAllOff() {
        when(port.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThat(processor.get(MEMBER_ID)).isEqualTo(new MemberNotificationSettingInfo(false, false));
        verify(memberQueryProcessor).getActiveMember(MEMBER_ID);
        verify(port, never()).insert(any());
    }

    @Test
    @DisplayName("저장된 행이 있으면 그 값이다")
    void getReturnsSavedValues() {
        when(port.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(new MemberNotificationSetting(7L, MEMBER_ID, false, true)));

        assertThat(processor.get(MEMBER_ID)).isEqualTo(new MemberNotificationSettingInfo(false, true));
    }

    @Test
    @DisplayName("행이 없으면 모두 꺼짐을 바탕으로 보낸 항목만 켠 새 행을 Snowflake ID 로 넣고, 부분 갱신은 부르지 않는다")
    void firstChangeInsertsFromAllOff() {
        when(port.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        MemberNotificationSettingInfo result = processor.change(MEMBER_ID, new MemberNotificationSettingChangeCommand(null, true));

        assertThat(result).isEqualTo(new MemberNotificationSettingInfo(false, true));
        ArgumentCaptor<MemberNotificationSetting> captor = ArgumentCaptor.forClass(MemberNotificationSetting.class);
        verify(port).insert(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new MemberNotificationSetting(900L, MEMBER_ID, false, true));
        verify(port, never()).changeByMemberId(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("행이 있으면 보낸 항목만(보내지 않은 항목은 null) 부분 갱신하고, 다시 읽은 값을 돌려준다 — 새 행을 넣지 않는다")
    void laterChangeUpdatesOnlySentItems() {
        when(port.findByMemberId(MEMBER_ID))
            .thenReturn(Optional.of(new MemberNotificationSetting(7L, MEMBER_ID, true, false)))
            // 갱신 뒤 다시 읽은 값 — 그 사이 다른 요청이 켠 regionNotice 가 남아 있다.
            .thenReturn(Optional.of(new MemberNotificationSetting(7L, MEMBER_ID, false, true)));
        when(port.changeByMemberId(eq(MEMBER_ID), eq(false), isNull(), any())).thenReturn(1);

        MemberNotificationSettingInfo result = processor.change(MEMBER_ID, new MemberNotificationSettingChangeCommand(false, null));

        assertThat(result).isEqualTo(new MemberNotificationSettingInfo(false, true));
        ArgumentCaptor<LocalDateTime> changedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(port).changeByMemberId(eq(MEMBER_ID), eq(false), isNull(), changedAt.capture());
        assertThat(changedAt.getValue()).isNotNull();
        verify(port, never()).insert(any());
        verifyNoInteractions(snowflakeIdGenerator);
    }

    @Test
    @DisplayName("조회한 행이 갱신 전에 사라졌으면(갱신 0건) MEMBER_013 으로 끝낸다 — 새 트랜잭션에서 다시 판단하게 하고 여기서 넣지 않는다")
    void vanishedRowBecomesRetryableConflict() {
        when(port.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(new MemberNotificationSetting(7L, MEMBER_ID, true, false)));
        when(port.changeByMemberId(anyLong(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> processor.change(MEMBER_ID, new MemberNotificationSettingChangeCommand(null, true)))
            .isInstanceOfSatisfying(MemberException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.NOTIFICATION_SETTING_SAVE_CONFLICT));
        verify(port, never()).insert(any());
    }

    @Test
    @DisplayName("탈퇴 · 정지 · 없는 회원이면 회원 예외로 끝나고 설정을 읽거나 쓰지 않는다")
    void inactiveMemberTouchesNothing() {
        when(memberQueryProcessor.getActiveMember(MEMBER_ID)).thenThrow(new MemberException(MemberErrorCode.WITHDRAWN_MEMBER));

        assertThatThrownBy(() -> processor.change(MEMBER_ID, new MemberNotificationSettingChangeCommand(true, null)))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER));
        assertThatThrownBy(() -> processor.get(MEMBER_ID))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER));
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("동시 첫 변경으로 삽입이 막히면 MEMBER_013 을 그대로 올린다 — 다시 하는 것은 새 트랜잭션을 여는 Facade 의 몫이다")
    void insertConflictPropagates() {
        when(port.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        when(port.insert(any())).thenThrow(new MemberException(MemberErrorCode.NOTIFICATION_SETTING_SAVE_CONFLICT));

        assertThatThrownBy(() -> processor.change(MEMBER_ID, new MemberNotificationSettingChangeCommand(true, null)))
            .isInstanceOfSatisfying(MemberException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.NOTIFICATION_SETTING_SAVE_CONFLICT));
    }
}
