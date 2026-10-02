package com.sneezecast.domainlayer.region.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberRegionRepository;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.mapper.MemberRegionMapperImpl;
import com.sneezecast.domainlayer.region.domain.model.MemberRegion;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * member_region 이 entity-design §1-4 대로 만들어지는지, 저장소 어댑터의 삽입 · 변경 · unique 위반 변환을 H2 에 실제 스키마를 만들어 본다.
 * 마이그레이션 도구가 없어 매핑이 곧 스키마 정본이다.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({JpaAuditConfig.class, MemberRegionRepositoryAdapter.class, MemberRegionMapperImpl.class})
class MemberRegionPersistenceTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MemberRegionRepositoryAdapter adapter;

    @Autowired
    private MemberRegionRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    @DisplayName("member_region 컬럼이 entity-design §1-4 와 같다 — district_code 는 8자, 둘 다 NOT NULL")
    void columnsMatchEntityDesign() throws SQLException {
        Map<String, ColumnSpec> columns = columns();

        assertThat(columns.keySet()).containsExactlyInAnyOrder("ID", "MEMBER_ID", "DISTRICT_CODE", "CREATED_AT", "UPDATED_AT");
        assertThat(columns).containsEntry("DISTRICT_CODE", new ColumnSpec(8, false));
        assertThat(columns.get("MEMBER_ID").nullable()).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("코드 변경을 트랜잭션 밖에서 부르면 거부한다 — 분리된 엔티티를 바꿔 변경이 조용히 사라지는 일을 막는다")
    void changeDistrictCodeRequiresTransaction() {
        assertThatThrownBy(() -> adapter.changeDistrictCode(1L, "11230510"))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("member_id 에 uk_member_region_member_id unique 인덱스가 있다 — 회원당 1행을 DB 가 지킨다")
    void memberIdHasUniqueIndex() throws SQLException {
        // H2 는 unique 제약을 받치는 인덱스에 접미사(_INDEX_n)를 붙인다. 제약 이름은 그대로 uk_member_region_member_id 다.
        assertThat(uniqueIndexes()).anySatisfy((name, indexColumns) -> {
            assertThat(name).startsWith("UK_MEMBER_REGION_MEMBER_ID");
            assertThat(indexColumns).containsExactly("MEMBER_ID");
        });
    }

    @Test
    @DisplayName("DB FK 제약이 없고 모든 컬럼에 @Comment 가 있다")
    void noForeignKeyAndEveryColumnCommented() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (ResultSet keys = connection.getMetaData().getImportedKeys(null, null, "MEMBER_REGION")) {
                assertThat(keys.next()).isFalse();
            }
            try (ResultSet rs = connection.getMetaData().getColumns(null, null, "MEMBER_REGION", null)) {
                while (rs.next()) {
                    assertThat(rs.getString("REMARKS")).as("member_region.%s", rs.getString("COLUMN_NAME")).isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("새 행을 넣고 회원으로 찾는다 — 감사 컬럼이 채워지고, 다른 회원은 empty")
    void insertsAndFindsByMemberId() {
        adapter.insert(region(1L, 42L, "11230510"));

        assertThat(adapter.findByMemberId(42L)).contains(region(1L, 42L, "11230510"));
        assertThat(adapter.findByMemberId(43L)).isEmpty();
        assertThat(repository.findById(1L)).hasValueSatisfying(entity -> assertThat(entity.getCreatedAt()).isNotNull());
    }

    @Test
    @DisplayName("조회한 행의 코드를 바꾸면 같은 ID 그대로 변경 감지로 반영된다")
    void changesDistrictCodeByDirtyChecking() {
        adapter.insert(region(1L, 42L, "11230510"));
        testEntityManager.flush();
        testEntityManager.clear();

        MemberRegion found = adapter.findByMemberId(42L).orElseThrow();
        MemberRegion changed = adapter.changeDistrictCode(found.id(), "21120561");
        testEntityManager.flush();
        testEntityManager.clear();

        assertThat(changed).isEqualTo(region(1L, 42L, "21120561"));
        assertThat(new JdbcTemplate(dataSource).queryForList("select district_code from member_region where member_id = 42", String.class))
            .containsExactly("21120561");
    }

    @Test
    @DisplayName("같은 회원에 두 번째 행을 넣으면(동시 첫 저장) unique 위반이 409 REGION_003 도메인 예외로 바뀐다 — 500 이 아니다")
    void secondRowOfSameMemberBecomesConflict() {
        adapter.insert(region(1L, 42L, "11230510"));

        assertThatThrownBy(() -> adapter.insert(region(2L, 42L, "21120561")))
            .isInstanceOfSatisfying(RegionException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RegionErrorCode.REGION_SAVE_CONFLICT));
    }

    @Test
    @DisplayName("다른 제약 위반(같은 ID)은 409 로 바꾸지 않는다 — 기존 행을 덮어쓰지도 않는다(Persistable)")
    void otherViolationIsNotConflict() {
        adapter.insert(region(7L, 42L, "11230510"));
        testEntityManager.clear();

        assertThatThrownBy(() -> adapter.insert(region(7L, 43L, "21120561")))
            .isInstanceOf(DataIntegrityViolationException.class)
            .isNotInstanceOf(RegionException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject("select member_id from member_region where id = 7", Long.class)).isEqualTo(42L);
    }

    private static MemberRegion region(long id, long memberId, String districtCode) {
        return MemberRegion.builder().id(id).memberId(memberId).districtCode(districtCode).build();
    }

    private Map<String, List<String>> uniqueIndexes() throws SQLException {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getIndexInfo(null, null, "MEMBER_REGION", true, false)) {
            while (rs.next()) {
                if (rs.getBoolean("NON_UNIQUE") || rs.getString("INDEX_NAME") == null) {
                    continue;
                }
                indexes.computeIfAbsent(rs.getString("INDEX_NAME"), ignored -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
            }
        }
        return indexes;
    }

    private Map<String, ColumnSpec> columns() throws SQLException {
        Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getColumns(null, null, "MEMBER_REGION", null)) {
            while (rs.next()) {
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                columns.put(rs.getString("COLUMN_NAME"), new ColumnSpec(rs.getInt("COLUMN_SIZE"), nullable));
            }
        }
        return columns;
    }

    private record ColumnSpec(int size, boolean nullable) {
    }
}
