package com.sneezecast.domainlayer.region.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.mapper.MemberInterestRegionMapperImpl;
import com.sneezecast.domainlayer.region.domain.model.MemberInterestRegion;
import com.sneezecast.persistence.config.JpaAuditConfig;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * member_interest_region 이 entity-design §1-6 대로 만들어지는지, 저장소 어댑터의 삽입 · 순서 · 삭제 · unique 위반 변환을 H2 에 실제 스키마를
 * 만들어 본다. 마이그레이션 도구가 없어 매핑이 곧 스키마 정본이다.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({JpaAuditConfig.class, MemberInterestRegionRepositoryAdapter.class, MemberInterestRegionMapperImpl.class})
class MemberInterestRegionPersistenceTest {

    private static final String TABLE = "MEMBER_INTEREST_REGION";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MemberInterestRegionRepositoryAdapter adapter;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    @DisplayName("컬럼이 entity-design §1-6 과 같다 — district_code 는 8자, 모두 NOT NULL(감사 컬럼 제외)")
    void columnsMatchEntityDesign() throws SQLException {
        Map<String, ColumnSpec> columns = columns();

        assertThat(columns.keySet()).containsExactlyInAnyOrder("ID", "MEMBER_ID", "DISTRICT_CODE", "SLOT", "CREATED_AT", "UPDATED_AT");
        assertThat(columns).containsEntry("DISTRICT_CODE", new ColumnSpec("CHARACTER VARYING", 8, false));
        assertThat(columns.get("MEMBER_ID").nullable()).isFalse();
        assertThat(columns.get("SLOT")).isEqualTo(new ColumnSpec("INTEGER", columns.get("SLOT").size(), false));
    }

    @Test
    @DisplayName("두 unique 인덱스가 이름 · 컬럼 순서대로 있다 — (member_id, district_code) · (member_id, slot)")
    void hasBothUniqueIndexes() throws SQLException {
        // H2 는 unique 제약을 받치는 인덱스에 접미사(_INDEX_n)를 붙인다. 제약 이름은 그대로다.
        Map<String, List<String>> indexes = uniqueIndexes();

        assertThat(indexes).anySatisfy((name, indexColumns) -> {
            assertThat(name).startsWith("UK_MEMBER_INTEREST_REGION_MEMBER_ID_DISTRICT_CODE");
            assertThat(indexColumns).containsExactly("MEMBER_ID", "DISTRICT_CODE");
        });
        assertThat(indexes).anySatisfy((name, indexColumns) -> {
            assertThat(name).startsWith("UK_MEMBER_INTEREST_REGION_MEMBER_ID_SLOT");
            assertThat(indexColumns).containsExactly("MEMBER_ID", "SLOT");
        });
    }

    @Test
    @DisplayName("DB FK 제약이 없고 모든 컬럼에 @Comment 가 있다")
    void noForeignKeyAndEveryColumnCommented() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (ResultSet keys = connection.getMetaData().getImportedKeys(null, null, TABLE)) {
                assertThat(keys.next()).isFalse();
            }
            try (ResultSet rs = connection.getMetaData().getColumns(null, null, TABLE, null)) {
                while (rs.next()) {
                    assertThat(rs.getString("REMARKS")).as("member_interest_region.%s", rs.getString("COLUMN_NAME")).isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("회원의 행만 id 오름차순(고른 순서)으로 돌려준다 — 칸 번호 순서가 아니다")
    void findsByMemberIdInIdOrder() {
        adapter.insert(region(30L, 42L, "11230510", 1));
        adapter.insert(region(10L, 42L, "21120561", 3));
        adapter.insert(region(20L, 42L, "11230520", 2));
        adapter.insert(region(5L, 43L, "11230510", 1));
        testEntityManager.clear();

        assertThat(adapter.findByMemberId(42L)).extracting(MemberInterestRegion::id).containsExactly(10L, 20L, 30L);
        assertThat(adapter.findByMemberId(42L).get(0)).isEqualTo(region(10L, 42L, "21120561", 3));
        assertThat(adapter.findByMemberId(44L)).isEmpty();
    }

    @Test
    @DisplayName("같은 회원에 같은 코드를 다시 넣으면 unique 위반이 409 REGION_003 도메인 예외로 바뀐다")
    void sameDistrictBecomesConflict() {
        adapter.insert(region(1L, 42L, "11230510", 1));

        assertSaveConflict(() -> adapter.insert(region(2L, 42L, "11230510", 2)));
    }

    @Test
    @DisplayName("같은 회원의 같은 칸에 다른 코드를 넣으면 unique 위반이 409 REGION_003 도메인 예외로 바뀐다 — 상한을 DB 가 지킨다")
    void sameSlotBecomesConflict() {
        adapter.insert(region(1L, 42L, "11230510", 1));

        assertSaveConflict(() -> adapter.insert(region(2L, 42L, "21120561", 1)));
    }

    @Test
    @DisplayName("다른 회원은 같은 코드 · 같은 칸을 쓸 수 있다")
    void otherMemberIsIndependent() {
        adapter.insert(region(1L, 42L, "11230510", 1));
        adapter.insert(region(2L, 43L, "11230510", 1));

        assertThat(adapter.findByMemberId(43L)).singleElement().isEqualTo(region(2L, 43L, "11230510", 1));
    }

    @Test
    @DisplayName("다른 제약 위반(같은 ID)은 409 로 바꾸지 않는다 — 기존 행을 덮어쓰지도 않는다(Persistable)")
    void otherViolationIsNotConflict() {
        adapter.insert(region(7L, 42L, "11230510", 1));
        testEntityManager.clear();

        assertThatThrownBy(() -> adapter.insert(region(7L, 43L, "21120561", 1)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .isNotInstanceOf(RegionException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject("select member_id from member_interest_region where id = 7", Long.class)).isEqualTo(42L);
    }

    @Test
    @DisplayName("삭제는 그 회원의 그 코드만 지우고, 없는 코드는 0건으로 끝난다(멱등)")
    void deletesOnlyMatchingRow() {
        adapter.insert(region(1L, 42L, "11230510", 1));
        adapter.insert(region(2L, 42L, "21120561", 2));
        adapter.insert(region(3L, 43L, "11230510", 1));

        assertThat(adapter.deleteByMemberIdAndDistrictCode(42L, "11230510")).isEqualTo(1);
        assertThat(adapter.deleteByMemberIdAndDistrictCode(42L, "11230510")).isZero();

        assertThat(adapter.findByMemberId(42L)).extracting(MemberInterestRegion::districtCode).containsExactly("21120561");
        assertThat(adapter.findByMemberId(43L)).extracting(MemberInterestRegion::districtCode).containsExactly("11230510");
    }

    private static void assertSaveConflict(ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(RegionException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RegionErrorCode.REGION_SAVE_CONFLICT));
    }

    private static MemberInterestRegion region(long id, long memberId, String districtCode, int slot) {
        return MemberInterestRegion.builder().id(id).memberId(memberId).districtCode(districtCode).slot(slot).build();
    }

    private Map<String, List<String>> uniqueIndexes() throws SQLException {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getIndexInfo(null, null, TABLE, true, false)) {
            while (rs.next()) {
                if (rs.getBoolean("NON_UNIQUE") || rs.getString("INDEX_NAME") == null) {
                    continue;
                }
                // getIndexInfo 는 ORDINAL_POSITION 순으로 준다 — 담은 순서가 인덱스 컬럼 순서다.
                indexes.computeIfAbsent(rs.getString("INDEX_NAME"), ignored -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
            }
        }
        return indexes;
    }

    private Map<String, ColumnSpec> columns() throws SQLException {
        Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getColumns(null, null, TABLE, null)) {
            while (rs.next()) {
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                columns.put(rs.getString("COLUMN_NAME"), new ColumnSpec(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"), nullable));
            }
        }
        return columns;
    }

    private record ColumnSpec(String type, int size, boolean nullable) {
    }
}
