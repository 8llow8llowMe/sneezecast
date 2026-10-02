package com.sneezecast.domainlayer.district.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.district.application.exception.DistrictErrorCode;
import com.sneezecast.domainlayer.district.application.exception.DistrictException;
import com.sneezecast.domainlayer.district.application.info.DistrictInfo;
import com.sneezecast.domainlayer.district.application.port.out.DistrictRepositoryPort;
import com.sneezecast.domainlayer.district.domain.model.District;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DistrictQueryProcessorTest {

    private static final District YEOKSAM_1 = District.builder()
        .code("11230510").name("역삼1동").sidoName("서울특별시").sigunguName("강남구").build();
    private static final District RETIRED_NOKSAN = District.builder()
        .code("21120560").name("녹산동").sidoName("부산광역시").sigunguName("강서구").validToYear((short) 2024).build();

    private DistrictRepositoryPort districtRepositoryPort;
    private DistrictQueryProcessor processor;

    @BeforeEach
    void setUp() {
        districtRepositoryPort = mock(DistrictRepositoryPort.class);
        processor = new DistrictQueryProcessor(districtRepositoryPort);
    }

    @Test
    @DisplayName("검색은 프론트 계약 상한(20건)으로 포트를 부르고 Info 로 옮긴다")
    void searchUsesLimitAndMapsToInfo() {
        when(districtRepositoryPort.searchActive("역삼", 20)).thenReturn(List.of(YEOKSAM_1));

        assertThat(processor.search("역삼")).containsExactly(
            DistrictInfo.builder().code("11230510").name("역삼1동").sigungu("서울특별시 강남구").active(true).build());
        assertThat(DistrictQueryProcessor.SEARCH_LIMIT).isEqualTo(20);
    }

    @Test
    @DisplayName("현행 코드는 active=true 다")
    void getActiveDistrict() {
        when(districtRepositoryPort.findByCode("11230510")).thenReturn(Optional.of(YEOKSAM_1));

        DistrictInfo info = processor.getByCode("11230510");

        assertThat(info.active()).isTrue();
        assertThat(info.sigungu()).isEqualTo("서울특별시 강남구");
    }

    @Test
    @DisplayName("폐지 코드는 예외가 아니라 active=false 다")
    void getRetiredDistrict() {
        when(districtRepositoryPort.findByCode("21120560")).thenReturn(Optional.of(RETIRED_NOKSAN));

        DistrictInfo info = processor.getByCode("21120560");

        assertThat(info.code()).isEqualTo("21120560");
        assertThat(info.active()).isFalse();
    }

    @Test
    @DisplayName("없는 코드는 DISTRICT_NOT_FOUND(404) 다")
    void missingDistrictThrows() {
        when(districtRepositoryPort.findByCode("99999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.getByCode("99999999"))
            .isInstanceOf(DistrictException.class)
            .extracting(exception -> ((DistrictException) exception).getErrorCode())
            .isEqualTo(DistrictErrorCode.DISTRICT_NOT_FOUND);
    }
}
