package com.sneezecast.domainlayer.region.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.port.out.MemberInterestRegionRepositoryPort;
import com.sneezecast.domainlayer.region.application.port.out.MemberRegionRepositoryPort;
import com.sneezecast.domainlayer.region.domain.model.MemberInterestRegion;
import com.sneezecast.domainlayer.region.domain.model.MemberRegion;
import com.sneezecast.global.properties.RegionInterestProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemberInterestRegionProcessorTest {

    private static final long MEMBER_ID = 42L;
    private static final String YEOKSAM = "11230510";
    private static final String NOKSAN = "21120561";
    private static final String SAMSEONG = "11230520";
    private static final String MY_REGION = "11240660";

    private MemberInterestRegionRepositoryPort interestPort;
    private MemberRegionRepositoryPort memberRegionPort;
    private SnowflakeIdGenerator snowflakeIdGenerator;
    private MemberInterestRegionProcessor processor;

    @BeforeEach
    void setUp() {
        interestPort = mock(MemberInterestRegionRepositoryPort.class);
        memberRegionPort = mock(MemberRegionRepositoryPort.class);
        snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
        processor = new MemberInterestRegionProcessor(interestPort, memberRegionPort, snowflakeIdGenerator, new RegionInterestProperties(3));
        when(memberRegionPort.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(new MemberRegion(1L, MEMBER_ID, MY_REGION)));
        when(snowflakeIdGenerator.generateId()).thenReturn(900L);
    }

    @Test
    @DisplayName("처음 더하면 1번 칸에 Snowflake ID 로 넣고, 넣은 뒤 다시 읽은 목록을 돌려준다")
    void addsToFirstSlot() {
        List<MemberInterestRegion> after = List.of(region(900L, YEOKSAM, 1));
        when(interestPort.findByMemberId(MEMBER_ID)).thenReturn(List.of(), after);

        assertThat(processor.add(MEMBER_ID, YEOKSAM)).isEqualTo(after);
        assertThat(inserted()).isEqualTo(region(900L, YEOKSAM, 1));
    }

    @Test
    @DisplayName("가운데 칸을 지운 뒤 더하면 그 빈 칸을 다시 쓴다 — 순서는 칸이 아니라 id 다")
    void reusesFreedMiddleSlot() {
        // 1 · 3 번 칸이 남아 있다 (2 번 칸 동네를 지웠다).
        when(interestPort.findByMemberId(MEMBER_ID)).thenReturn(List.of(region(100L, YEOKSAM, 1), region(300L, NOKSAN, 3)));

        processor.add(MEMBER_ID, SAMSEONG);

        assertThat(inserted()).isEqualTo(region(900L, SAMSEONG, 2));
    }

    @Test
    @DisplayName("상한(3곳)이 찼으면 REGION_005 이고 넣지 않는다")
    void limitExceeded() {
        when(interestPort.findByMemberId(MEMBER_ID))
            .thenReturn(List.of(region(100L, YEOKSAM, 1), region(200L, NOKSAN, 2), region(300L, SAMSEONG, 3)));

        assertRegionError(() -> processor.add(MEMBER_ID, "11230530"), RegionErrorCode.INTEREST_REGION_LIMIT_EXCEEDED);
        verify(interestPort, never()).insert(any());
    }

    @Test
    @DisplayName("상한을 줄여 칸 번호가 범위 밖인 옛 행이 남아 있어도 개수로 막는다")
    void limitCountsRowsNotSlots() {
        processor = new MemberInterestRegionProcessor(interestPort, memberRegionPort, snowflakeIdGenerator, new RegionInterestProperties(2));
        when(interestPort.findByMemberId(MEMBER_ID)).thenReturn(List.of(region(100L, YEOKSAM, 1), region(300L, NOKSAN, 3)));

        assertRegionError(() -> processor.add(MEMBER_ID, SAMSEONG), RegionErrorCode.INTEREST_REGION_LIMIT_EXCEEDED);
        verify(interestPort, never()).insert(any());
    }

    @Test
    @DisplayName("이미 고른 동네면 REGION_006 이다 — 상한이 찼어도 '이미 고름' 을 먼저 알린다")
    void alreadySelected() {
        when(interestPort.findByMemberId(MEMBER_ID))
            .thenReturn(List.of(region(100L, YEOKSAM, 1), region(200L, NOKSAN, 2), region(300L, SAMSEONG, 3)));

        assertRegionError(() -> processor.add(MEMBER_ID, NOKSAN), RegionErrorCode.INTEREST_REGION_ALREADY_SELECTED);
        verify(interestPort, never()).insert(any());
    }

    @Test
    @DisplayName("내 동네와 같은 코드면 REGION_007 이고 관심 동네 목록도 읽지 않는다")
    void sameAsMyRegion() {
        assertRegionError(() -> processor.add(MEMBER_ID, MY_REGION), RegionErrorCode.INTEREST_REGION_SAME_AS_MY_REGION);
        verifyNoInteractions(interestPort);
    }

    @Test
    @DisplayName("내 동네가 없어도 더할 수 있다")
    void addsWithoutMyRegion() {
        when(memberRegionPort.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        when(interestPort.findByMemberId(MEMBER_ID)).thenReturn(List.of());

        processor.add(MEMBER_ID, MY_REGION);

        assertThat(inserted().districtCode()).isEqualTo(MY_REGION);
    }

    @Test
    @DisplayName("동시 추가 충돌 뒤 다시 읽어 그 코드가 있으면 '이미 고른 동네' REGION_006 으로 바꾼다 (원인은 남긴다)")
    void conflictWithSameCodeBecomesAlreadySelected() {
        RegionException conflict = new RegionException(RegionErrorCode.REGION_SAVE_CONFLICT);
        when(interestPort.findByMemberId(MEMBER_ID)).thenReturn(List.of(region(100L, YEOKSAM, 1)));

        RegionException result = processor.reclassifyAddConflict(MEMBER_ID, YEOKSAM, conflict);

        assertThat(result.getErrorCode()).isEqualTo(RegionErrorCode.INTEREST_REGION_ALREADY_SELECTED);
        assertThat(result.getCause()).isSameAs(conflict);
    }

    @Test
    @DisplayName("동시 추가 충돌 뒤 다시 읽어 그 코드가 없으면(다른 동네가 칸을 먼저 차지) 받은 REGION_003 그대로다")
    void conflictWithOtherCodeStaysSaveConflict() {
        RegionException conflict = new RegionException(RegionErrorCode.REGION_SAVE_CONFLICT);
        when(interestPort.findByMemberId(MEMBER_ID)).thenReturn(List.of(region(100L, NOKSAN, 1)));

        assertThat(processor.reclassifyAddConflict(MEMBER_ID, YEOKSAM, conflict)).isSameAs(conflict);
    }

    @Test
    @DisplayName("삭제는 지운 뒤 남은 목록을 돌려준다 — 목록에 없던 코드(0건)도 성공이다")
    void removeIsIdempotent() {
        List<MemberInterestRegion> remaining = List.of(region(100L, NOKSAN, 1));
        when(interestPort.deleteByMemberIdAndDistrictCode(MEMBER_ID, YEOKSAM)).thenReturn(0);
        when(interestPort.findByMemberId(MEMBER_ID)).thenReturn(remaining);

        assertThat(processor.remove(MEMBER_ID, YEOKSAM)).isEqualTo(remaining);
        verify(interestPort).deleteByMemberIdAndDistrictCode(MEMBER_ID, YEOKSAM);
        verifyNoInteractions(memberRegionPort);
    }

    @Test
    @DisplayName("목록 조회는 저장소 순서(id 오름차순)를 그대로 돌려준다")
    void findKeepsRepositoryOrder() {
        List<MemberInterestRegion> regions = List.of(region(100L, NOKSAN, 3), region(200L, YEOKSAM, 1));
        when(interestPort.findByMemberId(MEMBER_ID)).thenReturn(regions);

        assertThat(processor.findByMemberId(MEMBER_ID)).isEqualTo(regions);
    }

    private MemberInterestRegion inserted() {
        ArgumentCaptor<MemberInterestRegion> captor = ArgumentCaptor.forClass(MemberInterestRegion.class);
        verify(interestPort).insert(captor.capture());
        return captor.getValue();
    }

    private static MemberInterestRegion region(long id, String districtCode, int slot) {
        return MemberInterestRegion.builder().id(id).memberId(MEMBER_ID).districtCode(districtCode).slot(slot).build();
    }

    private static void assertRegionError(ThrowingCallable call, RegionErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RegionException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
