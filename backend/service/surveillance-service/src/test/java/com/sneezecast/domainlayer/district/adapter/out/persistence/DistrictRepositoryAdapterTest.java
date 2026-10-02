package com.sneezecast.domainlayer.district.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.district.DistrictH2TestSupport;
import com.sneezecast.domainlayer.district.domain.model.District;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * QueryDSL 검색 · 코드 조회를 실제 스키마(H2 MySQL 모드)에 질의해 본다. 커스텀 리포지토리는 컴파일로 검증되지 않는다 (coding-conventions §8-4).
 */
class DistrictRepositoryAdapterTest extends DistrictH2TestSupport {

    @Autowired
    private DistrictRepositoryAdapter districtRepositoryAdapter;

    @BeforeEach
    void setUp() {
        clearDistricts();
        // 코드 순서와 다르게 넣어 정렬을 쿼리가 하는지 본다.
        insertActive("11240660", "가락1동", "서울특별시", "송파구");
        insertActive("11230520", "역삼2동", "서울특별시", "강남구");
        insertActive("11230510", "역삼1동", "서울특별시", "강남구");
        // 2025 분동: 녹산동 21120560 → 21120561. 옛 코드는 폐지로 남는다.
        insert("21120560", "녹산동", "부산광역시", "강서구", RETIRED_IN_2024);
        insertActive("21120561", "녹산동", "부산광역시", "강서구");
    }

    @Test
    @DisplayName("동 이름에 검색어가 들어가면 일치하고, 결과는 코드 오름차순이다")
    void matchesDistrictNameOrderedByCode() {
        assertThat(codes(search("역삼"))).containsExactly("11230510", "11230520");
    }

    @Test
    @DisplayName("시도 이름만 · 시군구 이름만 들어간 검색어도 일치한다")
    void matchesSidoOrSigunguName() {
        assertThat(codes(search("서울"))).containsExactly("11230510", "11230520", "11240660");
        assertThat(codes(search("송파구"))).containsExactly("11240660");
        assertThat(codes(search("강남"))).containsExactly("11230510", "11230520");
    }

    @Test
    @DisplayName("\"서울특별시 강남구\" 처럼 시도와 시군구를 이어 쓴 검색어도 일치한다")
    void matchesCombinedSidoAndSigungu() {
        assertThat(codes(search("서울특별시 강남구"))).containsExactly("11230510", "11230520");
        assertThat(codes(search("특별시 송파"))).containsExactly("11240660");
    }

    @Test
    @DisplayName("폐지된 동은 이름 · 시도가 같아도 검색되지 않는다")
    void excludesRetiredDistricts() {
        assertThat(codes(search("녹산"))).containsExactly("21120561");
        assertThat(codes(search("부산광역시"))).containsExactly("21120561");
    }

    @Test
    @DisplayName("일치하는 동이 없으면 빈 목록이다")
    void noMatchIsEmpty() {
        assertThat(search("없는동네")).isEmpty();
    }

    @Test
    @DisplayName("결과는 limit 건에서 잘리고, 자르기 전에 코드 순으로 정렬한다")
    void limitsAfterOrderingByCode() {
        // 98000025 → 98000001 역순으로 넣는다.
        IntStream.rangeClosed(1, 25).map(index -> 26 - index)
            .forEach(index -> insertActive("980000%02d".formatted(index), "테스트%d동".formatted(index), "테스트도", "테스트군"));

        List<District> found = districtRepositoryAdapter.searchActive("테스트", 20);

        assertThat(found).hasSize(20);
        assertThat(found.getFirst().code()).isEqualTo("98000001");
        assertThat(found.getLast().code()).isEqualTo("98000020");
    }

    @Test
    @DisplayName("검색어의 % · _ · ! 는 와일드카드가 아니라 글자 그대로다 — '%' 가 전체 목록이 되지 않는다")
    void escapesLikeWildcards() {
        insertActive("99000001", "백%동", "테스트도", "테스트군");
        insertActive("99000002", "언더_동", "테스트도", "테스트군");
        insertActive("99000003", "느낌!동", "테스트도", "테스트군");

        assertThat(codes(search("%"))).containsExactly("99000001");
        assertThat(codes(search("%동"))).containsExactly("99000001");
        assertThat(codes(search("_"))).containsExactly("99000002");
        assertThat(codes(search("!"))).containsExactly("99000003");
        assertThat(codes(search("!%"))).isEmpty();
    }

    @Test
    @DisplayName("와일드카드 문자가 든 행이 없으면 '%' · '_' 검색은 빈 목록이다")
    void wildcardOnlyQueryMatchesNothing() {
        assertThat(search("%")).isEmpty();
        assertThat(search("_")).isEmpty();
        assertThat(search("%%")).isEmpty();
    }

    @Test
    @DisplayName("코드 조회는 현행 · 폐지를 모두 찾고, 폐지 여부는 도메인 모델이 판정한다")
    void findsByCodeRegardlessOfRetirement() {
        District active = districtRepositoryAdapter.findByCode("11230510").orElseThrow();
        District retired = districtRepositoryAdapter.findByCode("21120560").orElseThrow();

        assertThat(active.name()).isEqualTo("역삼1동");
        assertThat(active.sidoName()).isEqualTo("서울특별시");
        assertThat(active.sigunguName()).isEqualTo("강남구");
        assertThat(active.isActive()).isTrue();
        assertThat(retired.validToYear()).isEqualTo(RETIRED_IN_2024);
        assertThat(retired.isActive()).isFalse();
    }

    @Test
    @DisplayName("없는 코드는 빈 Optional 이다")
    void missingCodeIsEmpty() {
        assertThat(districtRepositoryAdapter.findByCode("99999999")).isEmpty();
    }

    private List<District> search(String keyword) {
        return districtRepositoryAdapter.searchActive(keyword, 20);
    }

    private static List<String> codes(List<District> districts) {
        return districts.stream().map(District::code).toList();
    }
}
