package com.sneezecast.domainlayer.district.adapter.out.persistence.repository.custom;

import static com.sneezecast.domainlayer.district.adapter.out.persistence.entity.QDistrictEntity.districtEntity;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sneezecast.domainlayer.district.adapter.out.persistence.entity.DistrictEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * 행정동 검색 (QueryDSL).
 *
 * <p>조건은 동 이름과 {@code 시도 + " " + 시군구} 두 개다. 시도 이름만 · 시군구 이름만 들어간 검색어도 이어 붙인 문자열에 들어가므로 따로 걸지
 * 않는다 — 걸어도 결과가 같고 LIKE 만 늘어난다. {@code "서울특별시 강남구"} 처럼 둘을 이어 쓴 검색어는 이어 붙인 쪽만 잡는다.
 *
 * <p><b>와일드카드 이스케이프</b>: QueryDSL 의 {@code contains} 는 {@code like '%..%' escape '!'} 로 나가고, 검색어 안의 {@code %} · {@code _} ·
 * {@code !} 앞에 이스케이프 문자를 붙인다. 그래서 검색어 {@code %} 가 전체 목록이 되지 않는다 — {@code like} 에 검색어를 직접 이어 붙이면
 * 이 보호가 사라지므로 바꾸지 않는다 (H2 슬라이스 테스트가 고정한다).
 *
 * <p>앞이 열린 LIKE 라 인덱스를 타지 못하고 현행 행(약 3,500건)을 훑는다. 행 수가 작고 batch 적재 외에는 바뀌지 않아 받아들인다.
 */
@RequiredArgsConstructor
public class DistrictCustomRepositoryImpl implements DistrictCustomRepository {

    private static final String LABEL_DELIMITER = " ";

    private final JPAQueryFactory queryFactory;

    @Override
    public List<DistrictEntity> searchActive(String keyword, int limit) {
        return queryFactory
            .selectFrom(districtEntity)
            .where(
                districtEntity.validToYear.isNull(),
                districtEntity.name.contains(keyword)
                    .or(districtEntity.sidoName.concat(LABEL_DELIMITER).concat(districtEntity.sigunguName).contains(keyword)))
            .orderBy(districtEntity.code.asc())
            .limit(limit)
            .fetch();
    }
}
