package com.sneezecast.domainlayer.region.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sneezecast.domainlayer.region.adapter.in.web.dto.response.MemberRegionResponse;
import com.sneezecast.domainlayer.region.adapter.out.persistence.MemberRegionRepositoryAdapter;
import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberRegionEntity;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberRegionRepository;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.mapper.MemberRegionMapperImpl;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.domainlayer.region.application.service.presenter.RegionPresenter;
import com.sneezecast.domainlayer.region.application.service.processor.MemberRegionProcessor;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 내 동네 저장 · 조회를 <b>실제 {@code @Transactional} 프록시</b>와 H2 로 본다 — 원격 조회가 트랜잭션 밖에서 일어나는지, 검증 실패 · 장애면 저장이
 * 일어나지 않는지, 회원당 1행 upsert 와 동시 첫 저장의 409 변환이 실제 커밋 · 롤백 경로에서 맞는지.
 *
 * <p>{@code @DataJpaTest} 의 테스트 트랜잭션을 끈다({@code NOT_SUPPORTED}) — 켜 두면 모든 호출이 바깥 트랜잭션 안이라 "원격 조회가 트랜잭션 밖"
 * 을 볼 수 없다. 대신 테스트마다 테이블을 비운다. surveillance 조회 포트는 호출 시점의 트랜잭션 여부를 기록하는 가짜다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({
    JpaAuditConfig.class, RegionWebFacade.class, MemberRegionProcessor.class, RegionPresenter.class,
    MemberRegionRepositoryAdapter.class, MemberRegionMapperImpl.class, RegionWebFacadeTransactionTest.Beans.class
})
class RegionWebFacadeTransactionTest {

    private static final long MEMBER_ID = 42L;
    private static final String YEOKSAM = "11230510";
    private static final String NOKSAN = "21120561";

    @Autowired
    private RegionWebFacade facade;

    @Autowired
    private MemberRegionRepository repository;

    @Autowired
    private FakeDistrictQueryPort districtQueryPort;

    @MockitoSpyBean
    private MemberRegionRepositoryAdapter repositoryAdapter;

    @BeforeEach
    void setUp() {
        districtQueryPort.reset();
        districtQueryPort.put(new DistrictQueryResult(YEOKSAM, "역삼1동", "서울특별시 강남구", true));
        districtQueryPort.put(new DistrictQueryResult(NOKSAN, "녹산동", "부산광역시 강서구", true));
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("처음 저장하면 1행이 커밋되고, 응답은 방금 확인한 행정동이다")
    void firstSaveInsertsRow() {
        MemberRegionResponse response = facade.saveMyRegion(MEMBER_ID, YEOKSAM);

        assertThat(response).isEqualTo(new MemberRegionResponse(YEOKSAM, "역삼1동", "서울특별시 강남구", false));
        assertThat(repository.findAll()).singleElement().satisfies(entity -> {
            assertThat(entity.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(entity.getDistrictCode()).isEqualTo(YEOKSAM);
        });
    }

    @Test
    @DisplayName("다시 저장하면 같은 행(같은 ID)의 코드만 바뀐다 — 같은 코드로 다시 저장해도 성공이다")
    void resaveChangesSameRow() {
        facade.saveMyRegion(MEMBER_ID, YEOKSAM);
        long id = repository.findAll().get(0).getId();

        facade.saveMyRegion(MEMBER_ID, NOKSAN);
        assertThat(repository.findAll()).extracting(MemberRegionEntity::getId, MemberRegionEntity::getDistrictCode)
            .containsExactly(tuple(id, NOKSAN));

        assertThat(facade.saveMyRegion(MEMBER_ID, NOKSAN).code()).isEqualTo(NOKSAN);
        assertThat(repository.findAll()).extracting(MemberRegionEntity::getId).containsExactly(id);
    }

    @Test
    @DisplayName("surveillance 조회는 트랜잭션 밖에서, 저장은 트랜잭션 안에서 일어난다")
    void remoteLookupRunsOutsideTransaction() {
        List<Boolean> insertTransactionActive = new ArrayList<>();
        doAnswer(invocation -> {
            insertTransactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(repositoryAdapter).insert(any());

        facade.saveMyRegion(MEMBER_ID, YEOKSAM);
        facade.getMyRegion(MEMBER_ID);

        assertThat(districtQueryPort.transactionActiveAtCall).containsExactly(false, false);
        assertThat(insertTransactionActive).containsExactly(true);
    }

    @Test
    @DisplayName("폐지 · 없는 코드 · 장애면 저장 구간에 닿지 않는다 — 검증하지 못한 코드는 저장하지 않는다")
    void rejectedOrUnavailableLookupSavesNothing() {
        districtQueryPort.put(new DistrictQueryResult("21120560", "녹산동", "부산광역시 강서구", false));

        assertRegionError(() -> facade.saveMyRegion(MEMBER_ID, "21120560"), RegionErrorCode.DISTRICT_ABOLISHED);
        assertRegionError(() -> facade.saveMyRegion(MEMBER_ID, "99999999"), RegionErrorCode.DISTRICT_NOT_FOUND);
        districtQueryPort.failing = true;
        assertRegionError(() -> facade.saveMyRegion(MEMBER_ID, YEOKSAM), RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE);

        assertThat(repository.count()).isZero();
        verify(repositoryAdapter, never()).findByMemberId(anyLong());
        verify(repositoryAdapter, never()).insert(any());
    }

    @Test
    @DisplayName("동시 첫 저장(조회 땐 없던 행이 삽입 땐 있다)은 409 REGION_003 이다 — 500 · UnexpectedRollback 이 아니고 먼저 저장된 행은 그대로다")
    void concurrentFirstSaveBecomesConflict() {
        facade.saveMyRegion(MEMBER_ID, YEOKSAM);
        // 다른 요청이 먼저 넣은 상황: 이 요청의 조회 시점에는 행이 없었다.
        doReturn(Optional.empty()).when(repositoryAdapter).findByMemberId(MEMBER_ID);

        assertRegionError(() -> facade.saveMyRegion(MEMBER_ID, NOKSAN), RegionErrorCode.REGION_SAVE_CONFLICT);
        assertThat(repository.findAll()).extracting(MemberRegionEntity::getDistrictCode).containsExactly(YEOKSAM);
    }

    @Test
    @DisplayName("아직 고르지 않았으면 조회는 null 이고 surveillance 를 부르지 않는다")
    void getWithoutRegionIsNull() {
        assertThat(facade.getMyRegion(MEMBER_ID)).isNull();
        assertThat(districtQueryPort.transactionActiveAtCall).isEmpty();
    }

    @Test
    @DisplayName("저장 뒤 행정동이 폐지되면 조회는 abolished=true 이고 저장 행은 바뀌지 않는다")
    void getShowsAbolishedWithoutChangingRow() {
        facade.saveMyRegion(MEMBER_ID, YEOKSAM);
        districtQueryPort.put(new DistrictQueryResult(YEOKSAM, "역삼1동", "서울특별시 강남구", false));

        assertThat(facade.getMyRegion(MEMBER_ID)).isEqualTo(new MemberRegionResponse(YEOKSAM, "역삼1동", "서울특별시 강남구", true));
        assertThat(repository.findAll()).extracting(MemberRegionEntity::getDistrictCode).containsExactly(YEOKSAM);
    }

    private static void assertRegionError(ThrowingCallable call, RegionErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RegionException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    /** surveillance 대신 쓰는 가짜. 부를 때마다 그 순간 실제 트랜잭션이 열려 있었는지 남긴다. */
    static class FakeDistrictQueryPort implements DistrictQueryPort {

        private final Map<String, DistrictQueryResult> districts = new HashMap<>();
        private final List<Boolean> transactionActiveAtCall = new ArrayList<>();
        private boolean failing;

        @Override
        public Optional<DistrictQueryResult> findByCode(String code) {
            transactionActiveAtCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (failing) {
                throw new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
            }
            return Optional.ofNullable(districts.get(code));
        }

        void put(DistrictQueryResult district) {
            districts.put(district.code(), district);
        }

        void reset() {
            districts.clear();
            transactionActiveAtCall.clear();
            failing = false;
        }
    }

    @TestConfiguration
    static class Beans {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(0, 0);
        }

        @Bean
        FakeDistrictQueryPort fakeDistrictQueryPort() {
            return new FakeDistrictQueryPort();
        }
    }
}
