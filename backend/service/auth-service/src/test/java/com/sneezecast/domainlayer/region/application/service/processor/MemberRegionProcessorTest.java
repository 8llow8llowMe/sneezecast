package com.sneezecast.domainlayer.region.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.info.MemberRegionInfo;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.domainlayer.region.application.port.out.MemberRegionRepositoryPort;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.domainlayer.region.domain.model.MemberRegion;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemberRegionProcessorTest {

    private static final String CODE = "11230510";
    private static final DistrictQueryResult ACTIVE = new DistrictQueryResult(CODE, "역삼1동", "서울특별시 강남구", true);

    private MemberRegionRepositoryPort repositoryPort;
    private DistrictQueryPort districtQueryPort;
    private SnowflakeIdGenerator snowflakeIdGenerator;
    private MemberRegionProcessor processor;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(MemberRegionRepositoryPort.class);
        districtQueryPort = mock(DistrictQueryPort.class);
        snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
        processor = new MemberRegionProcessor(repositoryPort, districtQueryPort, snowflakeIdGenerator);
    }

    @Test
    @DisplayName("현행 행정동이면 그대로 돌려준다")
    void activeDistrictIsSelectable() {
        when(districtQueryPort.findByCode(CODE)).thenReturn(Optional.of(ACTIVE));

        assertThat(processor.requireSelectableDistrict(CODE)).isEqualTo(ACTIVE);
    }

    @Test
    @DisplayName("폐지된 행정동은 REGION_002 로 막는다")
    void abolishedDistrictIsRejected() {
        when(districtQueryPort.findByCode(CODE)).thenReturn(Optional.of(new DistrictQueryResult(CODE, "역삼1동", "서울특별시 강남구", false)));

        assertRegionError(() -> processor.requireSelectableDistrict(CODE), RegionErrorCode.DISTRICT_ABOLISHED);
    }

    @Test
    @DisplayName("없는 행정동은 REGION_001 로 막는다")
    void unknownDistrictIsRejected() {
        when(districtQueryPort.findByCode(CODE)).thenReturn(Optional.empty());

        assertRegionError(() -> processor.requireSelectableDistrict(CODE), RegionErrorCode.DISTRICT_NOT_FOUND);
    }

    @Test
    @DisplayName("surveillance 장애(REGION_004)는 그대로 올린다")
    void unavailableIsPropagated() {
        when(districtQueryPort.findByCode(CODE)).thenThrow(new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE));

        assertRegionError(() -> processor.requireSelectableDistrict(CODE), RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
        verifyNoInteractions(repositoryPort);
    }

    @Test
    @DisplayName("행이 없으면 Snowflake ID 로 새 행을 넣는다")
    void savesNewRow() {
        when(repositoryPort.findByMemberId(42L)).thenReturn(Optional.empty());
        when(snowflakeIdGenerator.generateId()).thenReturn(1001L);
        when(repositoryPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        processor.save(42L, CODE);

        ArgumentCaptor<MemberRegion> inserted = ArgumentCaptor.forClass(MemberRegion.class);
        verify(repositoryPort).insert(inserted.capture());
        assertThat(inserted.getValue()).isEqualTo(new MemberRegion(1001L, 42L, CODE));
        verify(repositoryPort, never()).changeDistrictCode(anyLong(), any());
    }

    @Test
    @DisplayName("행이 있으면 새 행을 만들지 않고 기존 행의 코드만 바꾼다 — 새로 매핑한 엔티티로 덮어쓰지 않는다")
    void changesExistingRow() {
        when(repositoryPort.findByMemberId(42L)).thenReturn(Optional.of(new MemberRegion(7L, 42L, "21120560")));
        when(repositoryPort.changeDistrictCode(7L, CODE)).thenReturn(new MemberRegion(7L, 42L, CODE));

        assertThat(processor.save(42L, CODE)).isEqualTo(new MemberRegion(7L, 42L, CODE));
        verify(repositoryPort, never()).insert(any());
        verifyNoInteractions(snowflakeIdGenerator);
    }

    @Test
    @DisplayName("저장된 코드를 보여 줄 때 폐지 여부는 surveillance 의 active 를 뒤집은 값이다")
    void describesWithCurrentAbolishedState() {
        when(districtQueryPort.findByCode(CODE)).thenReturn(Optional.of(new DistrictQueryResult(CODE, "역삼1동", "서울특별시 강남구", false)));

        assertThat(processor.describe(CODE)).isEqualTo(new MemberRegionInfo(CODE, "역삼1동", "서울특별시 강남구", true));
    }

    @Test
    @DisplayName("surveillance 에 코드가 없으면 폐지로 보고 이름 없이 돌려준다 — 저장 행은 건드리지 않는다")
    void describesMissingDistrictAsAbolished() {
        when(districtQueryPort.findByCode(CODE)).thenReturn(Optional.empty());

        assertThat(processor.describe(CODE)).isEqualTo(new MemberRegionInfo(CODE, null, null, true));
        verifyNoInteractions(repositoryPort);
    }

    @Test
    @DisplayName("조회 중 surveillance 장애는 REGION_004 로 올린다")
    void describeUnavailableIsPropagated() {
        when(districtQueryPort.findByCode(CODE)).thenThrow(new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE));

        assertRegionError(() -> processor.describe(CODE), RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
    }

    private static void assertRegionError(ThrowingCallable call, RegionErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RegionException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
