package com.sneezecast.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.mapper.MemberNotificationSettingMapperImpl;
import com.sneezecast.domainlayer.member.domain.model.MemberNotificationSetting;
import com.sneezecast.persistence.config.JpaAuditConfig;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
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

/**
 * member_notification_setting 이 entity-design §1-7 대로 만들어지는지, 저장소 어댑터의 조회 · 삽입 · 변경 · 회원 단위 삭제 · unique 위반 변환을 H2 에
 * 실제 스키마를 만들어 본다. 마이그레이션 도구가 없어 매핑이 곧 스키마 정본이다(prod DDL 은 이 매핑에서 옮긴다).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({JpaAuditConfig.class, MemberNotificationSettingRepositoryAdapter.class, MemberNotificationSettingMapperImpl.class})
class MemberNotificationSettingPersistenceTest {

    private static final String TABLE = "MEMBER_NOTIFICATION_SETTING";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MemberNotificationSettingRepositoryAdapter adapter;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    @DisplayName("컬럼이 entity-design §1-7 과 같다 — 두 설정은 NOT NULL boolean 이다")
    void columnsMatchEntityDesign() throws SQLException {
        Map<String, ColumnSpec> columns = columns();

        assertThat(columns.keySet()).containsExactlyInAnyOrder("ID", "MEMBER_ID", "WEEKLY_REPORT", "REGION_NOTICE", "CREATED_AT", "UPDATED_AT");
        assertThat(columns.get("MEMBER_ID").nullable()).isFalse();
        assertThat(columns.get("WEEKLY_REPORT")).satisfies(column -> {
            assertThat(column.type()).isEqualTo("BOOLEAN");
            assertThat(column.nullable()).isFalse();
        });
        assertThat(columns.get("REGION_NOTICE")).satisfies(column -> {
            assertThat(column.type()).isEqualTo("BOOLEAN");
            assertThat(column.nullable()).isFalse();
        });
    }

    @Test
    @DisplayName("member_id 에 uk_member_notification_setting_member_id unique 인덱스가 있다 — 회원당 1행을 DB 가 지킨다")
    void hasMemberIdUniqueIndex() throws SQLException {
        // H2 는 unique 제약을 받치는 인덱스에 접미사(_INDEX_n)를 붙인다. 제약 이름은 그대로다.
        assertThat(uniqueIndexes()).anySatisfy((name, indexColumns) -> {
            assertThat(name).startsWith("UK_MEMBER_NOTIFICATION_SETTING_MEMBER_ID");
            assertThat(indexColumns).containsExactly("MEMBER_ID");
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
                    assertThat(rs.getString("REMARKS")).as("member_notification_setting.%s", rs.getString("COLUMN_NAME")).isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("넣은 행을 회원으로 찾고 감사 컬럼이 채워진다. 다른 회원 · 행이 없는 회원은 empty 다")
    void insertsAndFindsByMemberId() {
        adapter.insert(setting(1L, 42L, true, false));
        adapter.insert(setting(2L, 43L, false, true));
        testEntityManager.clear();

        assertThat(adapter.findByMemberId(42L)).contains(setting(1L, 42L, true, false));
        assertThat(adapter.findByMemberId(43L)).contains(setting(2L, 43L, false, true));
        assertThat(adapter.findByMemberId(44L)).isEmpty();
        assertThat(jdbc().queryForObject("select created_at from member_notification_setting where id = 1", LocalDateTime.class)).isNotNull();
    }

    @Test
    @DisplayName("같은 회원에 두 번째 행을 넣으면 unique 위반이 409 MEMBER_013 도메인 예외로 바뀐다 — 500 이 아니다")
    void secondRowForSameMemberBecomesConflict() {
        adapter.insert(setting(1L, 42L, false, false));

        assertThatThrownBy(() -> adapter.insert(setting(2L, 42L, true, true)))
            .isInstanceOfSatisfying(MemberException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.NOTIFICATION_SETTING_SAVE_CONFLICT));
    }

    @Test
    @DisplayName("다른 제약 위반(같은 ID)은 409 로 바꾸지 않는다 — 기존 행을 덮어쓰지도 않는다(Persistable)")
    void otherViolationIsNotConflict() {
        adapter.insert(setting(7L, 42L, false, false));
        testEntityManager.clear();

        assertThatThrownBy(() -> adapter.insert(setting(7L, 43L, true, true)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .isNotInstanceOf(MemberException.class);
        assertThat(jdbc().queryForObject("select member_id from member_notification_setting where id = 7", Long.class)).isEqualTo(42L);
    }

    @Test
    @DisplayName("부분 갱신은 보낸 항목만 바꾸고 null 항목은 그대로 둔다 — 수정 시각을 쓰고, 같은 트랜잭션의 다음 조회가 바뀐 값을 읽는다")
    void partialChangeKeepsUnsentItems() {
        adapter.insert(setting(1L, 42L, false, true));
        adapter.insert(setting(2L, 43L, false, false));
        // 조회로 영속성 컨텍스트에 옛 값을 올려 둔다 — 갱신 뒤 이 캐시를 읽으면 안 된다.
        assertThat(adapter.findByMemberId(42L)).contains(setting(1L, 42L, false, true));
        LocalDateTime changedAt = LocalDateTime.of(2030, 1, 6, 9, 0);

        assertThat(adapter.changeByMemberId(42L, true, null, changedAt)).isEqualTo(1);
        assertThat(adapter.findByMemberId(42L)).contains(setting(1L, 42L, true, true));

        assertThat(adapter.changeByMemberId(42L, null, false, changedAt)).isEqualTo(1);
        assertThat(adapter.findByMemberId(42L)).contains(setting(1L, 42L, true, false));

        assertThat(jdbc().queryForObject("select updated_at from member_notification_setting where id = 1", LocalDateTime.class)).isEqualTo(changedAt);
        assertThat(adapter.findByMemberId(43L)).contains(setting(2L, 43L, false, false));
        assertThat(jdbc().queryForObject("select count(*) from member_notification_setting", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("행이 없는 회원의 부분 갱신은 0건이다 — 행을 만들지 않는다")
    void partialChangeWithoutRowIsZero() {
        assertThat(adapter.changeByMemberId(42L, true, true, LocalDateTime.now())).isZero();
        assertThat(adapter.findByMemberId(42L)).isEmpty();
    }

    @Test
    @DisplayName("회원 단위 삭제는 그 회원의 행만 지우고, 없으면 0건으로 끝난다(멱등) — 탈퇴 정리용")
    void deletesOnlyThatMembersRow() {
        adapter.insert(setting(1L, 42L, true, true));
        adapter.insert(setting(2L, 43L, true, false));

        assertThat(adapter.deleteByMemberId(42L)).isEqualTo(1);
        assertThat(adapter.deleteByMemberId(42L)).isZero();

        assertThat(adapter.findByMemberId(42L)).isEmpty();
        assertThat(adapter.findByMemberId(43L)).contains(setting(2L, 43L, true, false));
    }

    private static MemberNotificationSetting setting(long id, long memberId, boolean weeklyReport, boolean regionNotice) {
        return MemberNotificationSetting.builder().id(id).memberId(memberId).weeklyReport(weeklyReport).regionNotice(regionNotice).build();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private Map<String, List<String>> uniqueIndexes() throws SQLException {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getIndexInfo(null, null, TABLE, true, false)) {
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
             ResultSet rs = connection.getMetaData().getColumns(null, null, TABLE, null)) {
            while (rs.next()) {
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                columns.put(rs.getString("COLUMN_NAME"), new ColumnSpec(rs.getString("TYPE_NAME"), nullable));
            }
        }
        return columns;
    }

    private record ColumnSpec(String type, boolean nullable) {
    }
}
