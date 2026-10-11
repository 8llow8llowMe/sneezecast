package com.sneezecast.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberNotificationSettingResponse;
import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberNotificationSettingRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberNotificationSettingEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberNotificationSettingRepository;
import com.sneezecast.domainlayer.member.application.command.MemberNotificationSettingChangeCommand;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.mapper.MemberNotificationSettingMapperImpl;
import com.sneezecast.domainlayer.member.application.service.presenter.MemberPresenter;
import com.sneezecast.domainlayer.member.application.service.processor.MemberNotificationSettingProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberQueryProcessor;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 알림 설정 조회 · 변경을 <b>실제 {@code @Transactional} 프록시</b>와 H2 로 본다 — 조회가 행을 만들지 않는지, 보낸 항목만 DB 에서 바뀌어 같은 회원의
 * 겹친 요청이 서로의 항목을 되돌리지 않는지, 동시 첫 변경이 unique 에 막혀 롤백된 뒤 새 트랜잭션에서 다시 해 갱신으로 풀리는지(500 ·
 * UnexpectedRollback 이 아닌지), 조회한 행이 갱신 전에 사라지면 새 트랜잭션에서 다시 판단하는지, 회원 상태가 막으면 아무것도 쓰지 않는지.
 *
 * <p>{@code InterestRegionWebFacadeTransactionTest} 와 같은 방식이다 — 테스트 트랜잭션을 끄고({@code NOT_SUPPORTED}) 테스트마다 테이블을 비운다.
 * 겹친 요청은 첫 요청이 행을 읽은 순간 다른 스레드(따로 트랜잭션)에서 둘째 요청을 끝까지 커밋시켜 재현한다. 회원 상태 확인({@link MemberQueryProcessor})은
 * mock 이다(회원 · 동의 테이블은 이 테스트의 관심이 아니다).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({
    JpaAuditConfig.class, MemberNotificationSettingWebFacade.class, MemberNotificationSettingProcessor.class, MemberPresenter.class,
    MemberNotificationSettingRepositoryAdapter.class, MemberNotificationSettingMapperImpl.class, MemberNotificationSettingWebFacadeTransactionTest.Beans.class
})
class MemberNotificationSettingWebFacadeTransactionTest {

    private static final long MEMBER_ID = 42L;
    private static final ExecutorService OTHER_REQUEST = Executors.newSingleThreadExecutor();

    @Autowired
    private MemberNotificationSettingWebFacade facade;

    @Autowired
    private MemberNotificationSettingRepository repository;

    @MockitoBean
    private MemberQueryProcessor memberQueryProcessor;

    @MockitoSpyBean
    private MemberNotificationSettingRepositoryAdapter repositoryAdapter;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @AfterAll
    static void shutdown() {
        OTHER_REQUEST.shutdownNow();
    }

    @Test
    @DisplayName("한 번도 바꾸지 않았으면 모두 false 이고 조회는 행을 만들지 않는다")
    void getDoesNotCreateRow() {
        assertThat(facade.getMyNotificationSettings(MEMBER_ID)).isEqualTo(new MemberNotificationSettingResponse(false, false));
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("첫 변경이 행을 만들고, 다음 변경은 보낸 항목만 그 행에서 바꾼다 — 회원당 1행이고 수정 시각이 갱신된다")
    void changesOnlySentItemsOnOneRow() {
        assertThat(facade.changeMyNotificationSettings(MEMBER_ID, command(true, null))).isEqualTo(new MemberNotificationSettingResponse(true, false));
        LocalDateTime createdUpdatedAt = repository.findAll().getFirst().getUpdatedAt();
        assertThat(facade.changeMyNotificationSettings(MEMBER_ID, command(null, true))).isEqualTo(new MemberNotificationSettingResponse(true, true));
        assertThat(facade.changeMyNotificationSettings(MEMBER_ID, command(false, null))).isEqualTo(new MemberNotificationSettingResponse(false, true));

        assertThat(facade.getMyNotificationSettings(MEMBER_ID)).isEqualTo(new MemberNotificationSettingResponse(false, true));
        assertThat(repository.findAll()).singleElement().satisfies(entity -> {
            assertThat(entity.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(entity.isWeeklyReport()).isFalse();
            assertThat(entity.isRegionNotice()).isTrue();
            assertThat(entity.getUpdatedAt()).isAfterOrEqualTo(createdUpdatedAt);
        });
    }

    @Test
    @DisplayName("삽입 · 부분 갱신은 트랜잭션 안에서 일어나고, 저장소의 부분 갱신 · 회원 단위 삭제를 트랜잭션 없이 부르면 바로 실패한다(MANDATORY)")
    void writesRunInsideTransaction() {
        List<Boolean> transactionActive = new ArrayList<>();
        doAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(repositoryAdapter).insert(any());

        facade.changeMyNotificationSettings(MEMBER_ID, command(true, null));
        facade.changeMyNotificationSettings(MEMBER_ID, command(null, true));

        assertThat(transactionActive).containsExactly(true);
        assertThatThrownBy(() -> repositoryAdapter.changeByMemberId(MEMBER_ID, false, false, LocalDateTime.now()))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repositoryAdapter.deleteByMemberId(MEMBER_ID)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(repository.findAll()).singleElement().satisfies(entity -> {
            assertThat(entity.isWeeklyReport()).isTrue();
            assertThat(entity.isRegionNotice()).isTrue();
        });
    }

    @Test
    @DisplayName("행이 있을 때 겹친 두 요청이 서로 다른 항목을 바꾸면 둘 다 남는다 — 첫 요청이 읽은 뒤 둘째가 커밋해도 첫 요청이 둘째의 항목을 되돌리지 않는다")
    void overlappingChangesOfDifferentItemsBothSurvive() {
        facade.changeMyNotificationSettings(MEMBER_ID, command(false, false));
        // 첫 요청(이 스레드)이 행을 읽은 순간, 둘째 요청(다른 스레드 · 다른 트랜잭션)이 regionNotice 를 켜고 커밋한다.
        runOtherRequestAfterFirstRead(() -> facade.changeMyNotificationSettings(MEMBER_ID, command(null, true)));

        MemberNotificationSettingResponse first = facade.changeMyNotificationSettings(MEMBER_ID, command(true, null));

        assertThat(first).isEqualTo(new MemberNotificationSettingResponse(true, true));
        assertThat(repository.findAll()).singleElement().satisfies(entity -> {
            assertThat(entity.isWeeklyReport()).isTrue();
            assertThat(entity.isRegionNotice()).isTrue();
        });
        verify(repositoryAdapter, times(1)).insert(any());
    }

    @Test
    @DisplayName("같은 회원의 첫 변경이 겹쳐 unique 에 막히면 롤백 뒤 새 트랜잭션에서 다시 해 부분 갱신된다 — 먼저 들어간 요청이 켠 항목을 덮지 않는다")
    void concurrentFirstChangeIsRetriedAsUpdate() {
        // 다른 요청이 먼저 넣은 상황: regionNotice 만 켰다. 이 요청의 첫 조회에는 행이 없었고, 다시 할 때는 실제 행이 보인다.
        facade.changeMyNotificationSettings(MEMBER_ID, command(null, true));
        doReturn(Optional.empty()).doCallRealMethod().when(repositoryAdapter).findByMemberId(MEMBER_ID);

        MemberNotificationSettingResponse response = facade.changeMyNotificationSettings(MEMBER_ID, command(true, null));

        assertThat(response).isEqualTo(new MemberNotificationSettingResponse(true, true));
        assertThat(repository.findAll()).singleElement().satisfies(entity -> {
            assertThat(entity.isWeeklyReport()).isTrue();
            assertThat(entity.isRegionNotice()).isTrue();
        });
        // 처음 넣은 요청 + 이 요청의 막힌 첫 시도. 다시 할 때는 넣지 않고 부분 갱신한다.
        verify(repositoryAdapter, times(2)).insert(any());
    }

    @Test
    @DisplayName("첫 변경 경합이 다시 해도 겹치면 409 MEMBER_013 이고 행은 그대로다 — 한 번만 다시 한다")
    void secondConflictIsReported() {
        facade.changeMyNotificationSettings(MEMBER_ID, command(null, true));
        doReturn(Optional.empty()).when(repositoryAdapter).findByMemberId(MEMBER_ID);

        assertThatThrownBy(() -> facade.changeMyNotificationSettings(MEMBER_ID, command(true, null)))
            .isInstanceOfSatisfying(MemberException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.NOTIFICATION_SETTING_SAVE_CONFLICT));

        verify(repositoryAdapter, times(3)).insert(any());
        assertThat(repository.findAll()).singleElement().satisfies(entity -> {
            assertThat(entity.isWeeklyReport()).isFalse();
            assertThat(entity.isRegionNotice()).isTrue();
        });
    }

    @Test
    @DisplayName("읽은 행이 갱신 전에 지워지면(갱신 0건) 500 이 아니라 새 트랜잭션에서 다시 판단한다 — 회원이 그대로면 모두 꺼짐 바탕으로 새 행을 만든다")
    void vanishedRowIsRejudgedInNewTransaction() {
        facade.changeMyNotificationSettings(MEMBER_ID, command(true, true));
        runOtherRequestAfterFirstRead(() -> repository.deleteAll());

        MemberNotificationSettingResponse response = facade.changeMyNotificationSettings(MEMBER_ID, command(null, true));

        assertThat(response).isEqualTo(new MemberNotificationSettingResponse(false, true));
        assertThat(repository.findAll()).singleElement().satisfies(entity -> {
            assertThat(entity.isWeeklyReport()).isFalse();
            assertThat(entity.isRegionNotice()).isTrue();
        });
    }

    @Test
    @DisplayName("읽은 행이 탈퇴 정리로 지워졌으면 다시 판단할 때 회원 상태 오류(MEMBER_002)로 끝나고 행을 다시 만들지 않는다")
    void vanishedRowOfWithdrawnMemberEndsWithMemberError() {
        facade.changeMyNotificationSettings(MEMBER_ID, command(true, true));
        // 이 요청의 첫 확인은 통과하고(아직 ACTIVE), 다시 할 때는 탈퇴가 커밋된 뒤다.
        when(memberQueryProcessor.getActiveMember(MEMBER_ID)).thenReturn(null).thenThrow(new MemberException(MemberErrorCode.WITHDRAWN_MEMBER));
        runOtherRequestAfterFirstRead(() -> repository.deleteAll());

        assertThatThrownBy(() -> facade.changeMyNotificationSettings(MEMBER_ID, command(null, true)))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER));
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("탈퇴 · 정지 · 없는 회원이면 다시 하지 않고 회원 예외로 끝나며 행을 만들지 않는다")
    void inactiveMemberCreatesNothing() {
        when(memberQueryProcessor.getActiveMember(MEMBER_ID)).thenThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        assertThatThrownBy(() -> facade.changeMyNotificationSettings(MEMBER_ID, command(true, true)))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
        verify(memberQueryProcessor, times(1)).getActiveMember(MEMBER_ID);
        verify(repositoryAdapter, never()).insert(any());
        assertThat(repository.count()).isZero();
    }

    /**
     * 다음 {@code findByMemberId} 한 번(이 스레드의 요청이 행을 읽는 순간) 뒤에 다른 스레드에서 {@code otherRequest} 를 끝까지 돌리고(따로 트랜잭션 ·
     * 커밋) 기다린 다음 읽은 값을 돌려준다. 다른 스레드의 요청이 다시 부르는 {@code findByMemberId} 는 그대로 지나간다.
     */
    private void runOtherRequestAfterFirstRead(Runnable otherRequest) {
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object read = invocation.callRealMethod();
            if (armed.compareAndSet(true, false)) {
                OTHER_REQUEST.submit(otherRequest).get(10, TimeUnit.SECONDS);
            }
            return read;
        }).when(repositoryAdapter).findByMemberId(MEMBER_ID);
    }

    private static MemberNotificationSettingChangeCommand command(Boolean weeklyReport, Boolean regionNotice) {
        return new MemberNotificationSettingChangeCommand(weeklyReport, regionNotice);
    }

    @TestConfiguration
    static class Beans {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(0, 0);
        }
    }
}
