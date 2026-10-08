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
import com.sneezecast.domainlayer.region.adapter.out.persistence.MemberInterestRegionRepositoryAdapter;
import com.sneezecast.domainlayer.region.adapter.out.persistence.MemberRegionRepositoryAdapter;
import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberInterestRegionEntity;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberInterestRegionRepository;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberRegionRepository;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.mapper.MemberInterestRegionMapperImpl;
import com.sneezecast.domainlayer.region.application.mapper.MemberRegionMapperImpl;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.domainlayer.region.application.service.presenter.RegionPresenter;
import com.sneezecast.domainlayer.region.application.service.processor.MemberInterestRegionProcessor;
import com.sneezecast.domainlayer.region.application.service.processor.MemberRegionProcessor;
import com.sneezecast.global.properties.RegionInterestProperties;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.persistence.dto.SliceResponse;
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
 * 관심 동네 조회 · 추가 · 삭제를 <b>실제 {@code @Transactional} 프록시</b>와 H2 로 본다 — 원격 조회가 트랜잭션 밖에서 일어나는지, 검증 실패 ·
 * 장애면 DB 구간에 닿지 않는지, 동시 추가가 unique 에 막혀 롤백된 뒤 새 트랜잭션에서 다시 읽어 409 를 제대로 가르는지, 변경 뒤 이름 조회가
 * 실패해도 변경은 커밋돼 있는지.
 *
 * <p>{@code RegionWebFacadeTransactionTest} 와 같은 방식이다 — 테스트 트랜잭션을 끄고({@code NOT_SUPPORTED}) 테스트마다 테이블을 비우며,
 * surveillance 조회 포트는 호출 시점의 트랜잭션 여부를 기록하는 가짜다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({
    JpaAuditConfig.class, InterestRegionWebFacade.class, MemberInterestRegionProcessor.class, MemberRegionProcessor.class, RegionPresenter.class,
    MemberInterestRegionRepositoryAdapter.class, MemberInterestRegionMapperImpl.class, MemberRegionRepositoryAdapter.class, MemberRegionMapperImpl.class,
    InterestRegionWebFacadeTransactionTest.Beans.class
})
class InterestRegionWebFacadeTransactionTest {

    private static final long MEMBER_ID = 42L;
    private static final String YEOKSAM = "11230510";
    private static final String NOKSAN = "21120561";
    private static final String SAMSEONG = "11230520";
    private static final String DAECHI = "11230530";
    private static final String MY_REGION = "11240660";

    @Autowired
    private InterestRegionWebFacade facade;

    @Autowired
    private MemberInterestRegionRepository repository;

    @Autowired
    private MemberRegionRepository memberRegionRepository;

    @Autowired
    private MemberRegionProcessor memberRegionProcessor;

    @Autowired
    private FakeDistrictQueryPort districtQueryPort;

    @MockitoSpyBean
    private MemberInterestRegionRepositoryAdapter repositoryAdapter;

    @BeforeEach
    void setUp() {
        districtQueryPort.reset();
        districtQueryPort.put(new DistrictQueryResult(YEOKSAM, "역삼1동", "서울특별시 강남구", true));
        districtQueryPort.put(new DistrictQueryResult(NOKSAN, "녹산동", "부산광역시 강서구", true));
        districtQueryPort.put(new DistrictQueryResult(SAMSEONG, "삼성1동", "서울특별시 강남구", true));
        districtQueryPort.put(new DistrictQueryResult(DAECHI, "대치1동", "서울특별시 강남구", true));
        districtQueryPort.put(new DistrictQueryResult(MY_REGION, "역삼2동", "서울특별시 강남구", true));
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        memberRegionRepository.deleteAll();
    }

    @Test
    @DisplayName("하나도 없으면 빈 목록이고 surveillance 를 부르지 않는다")
    void emptyList() {
        assertThat(facade.getMyInterestRegions(MEMBER_ID)).isEqualTo(new SliceResponse<>(List.of(), false));
        assertThat(districtQueryPort.calledCodes).isEmpty();
    }

    @Test
    @DisplayName("더할 때마다 더한 뒤의 목록을 고른 순서로 돌려주고, 방금 확인한 코드는 다시 부르지 않는다")
    void addReturnsListInSelectionOrder() {
        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);
        districtQueryPort.calledCodes.clear();

        SliceResponse<MemberRegionResponse> response = facade.addMyInterestRegion(MEMBER_ID, NOKSAN);

        assertThat(response.hasNext()).isFalse();
        assertThat(response.contents()).containsExactly(
            new MemberRegionResponse(YEOKSAM, "역삼1동", "서울특별시 강남구", false),
            new MemberRegionResponse(NOKSAN, "녹산동", "부산광역시 강서구", false));
        // 확인(requireSelectableDistrict) 한 번 + 앞서 고른 동네 이름 한 번. 새 동네는 확인한 값을 그대로 쓴다.
        assertThat(districtQueryPort.calledCodes).containsExactly(NOKSAN, YEOKSAM);
    }

    @Test
    @DisplayName("surveillance 조회는 모두 트랜잭션 밖에서, 삽입은 트랜잭션 안에서 일어난다")
    void remoteLookupRunsOutsideTransaction() {
        List<Boolean> insertTransactionActive = new ArrayList<>();
        doAnswer(invocation -> {
            insertTransactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(repositoryAdapter).insert(any());

        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);
        facade.getMyInterestRegions(MEMBER_ID);
        facade.removeMyInterestRegion(MEMBER_ID, YEOKSAM);

        assertThat(districtQueryPort.transactionActiveAtCall).isNotEmpty().containsOnly(false);
        assertThat(insertTransactionActive).containsExactly(true);
    }

    @Test
    @DisplayName("폐지 · 없는 코드 · 장애면 DB 구간에 닿지 않는다 — 검증하지 못한 코드는 더하지 않는다")
    void rejectedOrUnavailableLookupSavesNothing() {
        districtQueryPort.put(new DistrictQueryResult("21120560", "녹산동", "부산광역시 강서구", false));

        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, "21120560"), RegionErrorCode.DISTRICT_ABOLISHED);
        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, "99999999"), RegionErrorCode.DISTRICT_NOT_FOUND);
        districtQueryPort.failing = true;
        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, YEOKSAM), RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE);

        assertThat(repository.count()).isZero();
        verify(repositoryAdapter, never()).findByMemberId(anyLong());
        verify(repositoryAdapter, never()).insert(any());
    }

    @Test
    @DisplayName("내 동네와 같으면 REGION_007, 이미 고른 동네면 REGION_006, 3곳이 찼으면 REGION_005 이고 행은 그대로다")
    void businessConflicts() {
        memberRegionProcessor.save(MEMBER_ID, MY_REGION);
        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, MY_REGION), RegionErrorCode.INTEREST_REGION_SAME_AS_MY_REGION);

        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);
        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, YEOKSAM), RegionErrorCode.INTEREST_REGION_ALREADY_SELECTED);

        facade.addMyInterestRegion(MEMBER_ID, NOKSAN);
        facade.addMyInterestRegion(MEMBER_ID, SAMSEONG);
        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, DAECHI), RegionErrorCode.INTEREST_REGION_LIMIT_EXCEEDED);

        assertThat(repository.findAll()).extracting(MemberInterestRegionEntity::getDistrictCode).containsExactlyInAnyOrder(YEOKSAM, NOKSAN, SAMSEONG);
    }

    @Test
    @DisplayName("가운데 동네를 지우고 더하면 그 칸을 다시 쓰고, 목록에서는 끝에 붙는다")
    void reusesFreedSlotButKeepsSelectionOrder() {
        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);
        facade.addMyInterestRegion(MEMBER_ID, NOKSAN);
        facade.addMyInterestRegion(MEMBER_ID, SAMSEONG);

        facade.removeMyInterestRegion(MEMBER_ID, NOKSAN);
        SliceResponse<MemberRegionResponse> response = facade.addMyInterestRegion(MEMBER_ID, DAECHI);

        assertThat(response.contents()).extracting(MemberRegionResponse::code).containsExactly(YEOKSAM, SAMSEONG, DAECHI);
        assertThat(repository.findAll()).extracting(MemberInterestRegionEntity::getDistrictCode, MemberInterestRegionEntity::getSlot)
            .containsExactlyInAnyOrder(tuple(YEOKSAM, 1), tuple(SAMSEONG, 3), tuple(DAECHI, 2));
    }

    @Test
    @DisplayName("같은 동네를 동시에 더해 unique 에 막히면 롤백 뒤 다시 읽어 REGION_006 이다 — 500 · UnexpectedRollback 이 아니다")
    void concurrentAddOfSameCodeBecomesAlreadySelected() {
        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);
        // 다른 요청이 먼저 넣은 상황: 이 요청의 첫 조회에는 행이 없었다. 롤백 뒤 다시 읽을 때는 실제 행이 보인다.
        doReturn(List.of()).doCallRealMethod().when(repositoryAdapter).findByMemberId(MEMBER_ID);

        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, YEOKSAM), RegionErrorCode.INTEREST_REGION_ALREADY_SELECTED);
        assertThat(repository.findAll()).extracting(MemberInterestRegionEntity::getDistrictCode).containsExactly(YEOKSAM);
    }

    @Test
    @DisplayName("다른 동네가 같은 칸을 먼저 차지해 막히면 REGION_003 이고, 이 요청의 동네는 들어가지 않는다 — 다시 보내면 다음 칸으로 들어간다")
    void concurrentAddOfOtherCodeIsSaveConflict() {
        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);
        doReturn(List.of()).doCallRealMethod().when(repositoryAdapter).findByMemberId(MEMBER_ID);

        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, NOKSAN), RegionErrorCode.REGION_SAVE_CONFLICT);
        assertThat(repository.findAll()).extracting(MemberInterestRegionEntity::getDistrictCode).containsExactly(YEOKSAM);

        assertThat(facade.addMyInterestRegion(MEMBER_ID, NOKSAN).contents()).extracting(MemberRegionResponse::code).containsExactly(YEOKSAM, NOKSAN);
    }

    @Test
    @DisplayName("고른 뒤 폐지되거나 surveillance 에서 사라진 동네도 목록에 남는다 — abolished=true, 사라졌으면 이름 null")
    void abolishedRegionsStayInList() {
        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);
        facade.addMyInterestRegion(MEMBER_ID, NOKSAN);
        districtQueryPort.put(new DistrictQueryResult(YEOKSAM, "역삼1동", "서울특별시 강남구", false));
        districtQueryPort.remove(NOKSAN);

        assertThat(facade.getMyInterestRegions(MEMBER_ID).contents()).containsExactly(
            new MemberRegionResponse(YEOKSAM, "역삼1동", "서울특별시 강남구", true),
            new MemberRegionResponse(NOKSAN, null, null, true));
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("삭제는 목록에 없는 코드여도 성공이고, 남은 목록을 돌려준다")
    void removeIsIdempotent() {
        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);

        assertThat(facade.removeMyInterestRegion(MEMBER_ID, NOKSAN).contents()).extracting(MemberRegionResponse::code).containsExactly(YEOKSAM);
        assertThat(facade.removeMyInterestRegion(MEMBER_ID, YEOKSAM).contents()).isEmpty();
        assertThat(facade.removeMyInterestRegion(MEMBER_ID, YEOKSAM).contents()).isEmpty();
    }

    @Test
    @DisplayName("변경 뒤 이름 조회가 실패하면 503 이지만 변경은 커밋돼 있다 — 다시 보낸 추가는 REGION_006 이다")
    void changeStaysCommittedWhenDescribeFails() {
        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);
        districtQueryPort.failingCodes.add(YEOKSAM);

        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, NOKSAN), RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
        assertThat(repository.findAll()).extracting(MemberInterestRegionEntity::getDistrictCode).containsExactlyInAnyOrder(YEOKSAM, NOKSAN);
        assertRegionError(() -> facade.addMyInterestRegion(MEMBER_ID, NOKSAN), RegionErrorCode.INTEREST_REGION_ALREADY_SELECTED);
    }

    @Test
    @DisplayName("삭제 뒤 남은 동네의 이름 조회가 실패하면 503 이지만 삭제는 커밋돼 있다 — 다시 보낸 삭제는 성공이다")
    void removeStaysCommittedWhenDescribeFails() {
        facade.addMyInterestRegion(MEMBER_ID, YEOKSAM);
        facade.addMyInterestRegion(MEMBER_ID, NOKSAN);
        districtQueryPort.failingCodes.add(YEOKSAM);

        assertRegionError(() -> facade.removeMyInterestRegion(MEMBER_ID, NOKSAN), RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
        assertThat(repository.findAll()).extracting(MemberInterestRegionEntity::getDistrictCode).containsExactly(YEOKSAM);

        districtQueryPort.failingCodes.clear();
        assertThat(facade.removeMyInterestRegion(MEMBER_ID, NOKSAN).contents()).extracting(MemberRegionResponse::code).containsExactly(YEOKSAM);
    }

    private static void assertRegionError(ThrowingCallable call, RegionErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RegionException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    /** surveillance 대신 쓰는 가짜. 부를 때마다 코드와 그 순간 실제 트랜잭션이 열려 있었는지 남긴다. */
    static class FakeDistrictQueryPort implements DistrictQueryPort {

        private final Map<String, DistrictQueryResult> districts = new HashMap<>();
        private final List<String> calledCodes = new ArrayList<>();
        private final List<Boolean> transactionActiveAtCall = new ArrayList<>();
        private final List<String> failingCodes = new ArrayList<>();
        private boolean failing;

        @Override
        public Optional<DistrictQueryResult> findByCode(String code) {
            calledCodes.add(code);
            transactionActiveAtCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (failing || failingCodes.contains(code)) {
                throw new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
            }
            return Optional.ofNullable(districts.get(code));
        }

        void put(DistrictQueryResult district) {
            districts.put(district.code(), district);
        }

        void remove(String code) {
            districts.remove(code);
        }

        void reset() {
            districts.clear();
            calledCodes.clear();
            transactionActiveAtCall.clear();
            failingCodes.clear();
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
        RegionInterestProperties regionInterestProperties() {
            return new RegionInterestProperties(3);
        }

        @Bean
        FakeDistrictQueryPort fakeDistrictQueryPort() {
            return new FakeDistrictQueryPort();
        }
    }
}
