package com.sneezecast.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberConsentEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberConsentRepository;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.mapper.MemberConsentMapperImpl;
import com.sneezecast.domainlayer.member.application.mapper.MemberMapperImpl;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.security.common.enums.SecurityRole;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
 * member · member_consent 가 entity-design §1-1 · §1-2 대로 만들어지는지 H2 에 실제 스키마를 만들어 본다. 마이그레이션 도구가 없어
 * dev 스키마는 ddl-auto 가 만들고 prod DDL 은 이 매핑에서 뽑으므로, 매핑이 곧 스키마 정본이다.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({JpaAuditConfig.class, MemberRepositoryAdapter.class, MemberConsentRepositoryAdapter.class, MemberMapperImpl.class, MemberConsentMapperImpl.class})
class MemberPersistenceSchemaTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MemberRepositoryAdapter memberRepositoryAdapter;

    @Autowired
    private MemberConsentRepositoryAdapter memberConsentRepositoryAdapter;

    @Autowired
    private MemberConsentRepository memberConsentRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    @DisplayName("member.email 에 uk_member_email unique 인덱스가 있다 — 동시 가입 중복을 DB 가 막는다")
    void memberEmailHasUniqueIndex() throws SQLException {
        // H2 는 unique 제약을 받치는 인덱스에 접미사(_INDEX_n)를 붙인다. 제약 이름은 그대로 uk_member_email 이다.
        assertThat(indexes("MEMBER", true)).anySatisfy((name, columns) -> {
            assertThat(name).startsWith("UK_MEMBER_EMAIL");
            assertThat(columns).containsExactly("EMAIL");
        });
    }

    @Test
    @DisplayName("member_consent 는 (member_id, type) 조회 인덱스만 있고 unique 가 아니다 — 항목당 여러 행이 쌓인다")
    void memberConsentHasNonUniqueMemberIdTypeIndex() throws SQLException {
        assertThat(indexes("MEMBER_CONSENT", false)).containsEntry("IDX_MEMBER_CONSENT_MEMBER_ID_TYPE", List.of("MEMBER_ID", "TYPE"));
        assertThat(indexes("MEMBER_CONSENT", true)).doesNotContainKey("IDX_MEMBER_CONSENT_MEMBER_ID_TYPE")
            .allSatisfy((name, columns) -> assertThat(columns).containsExactly("ID"));
    }

    @Test
    @DisplayName("member_consent 에 DB FK 제약이 없다 — 회원은 raw member_id 로만 참조한다")
    void memberConsentHasNoForeignKey() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet keys = connection.getMetaData().getImportedKeys(null, null, "MEMBER_CONSENT")) {
            assertThat(keys.next()).isFalse();
        }
    }

    @Test
    @DisplayName("member 컬럼이 entity-design §1-1 과 같다 — 성명 컬럼이 없고, 길이 · null 허용이 표와 같고, enum 은 네이티브 enum 이 아니라 VARCHAR 다")
    void memberColumnsMatchEntityDesign() throws SQLException {
        Map<String, ColumnSpec> columns = columns("MEMBER");

        assertThat(columns.keySet()).containsExactlyInAnyOrder("ID", "EMAIL", "PASSWORD", "NICKNAME", "PROFILE_IMAGE_URL", "PROFILE_IMAGE_KEY",
            "ROLE", "PROVIDER", "STATUS", "WITHDRAWN_AT", "CREATED_AT", "UPDATED_AT");
        assertThat(columns).containsEntry("EMAIL", new ColumnSpec(100, false))
            .containsEntry("PASSWORD", new ColumnSpec(80, true))
            .containsEntry("NICKNAME", new ColumnSpec(30, false))
            .containsEntry("PROFILE_IMAGE_URL", new ColumnSpec(500, true))
            .containsEntry("PROFILE_IMAGE_KEY", new ColumnSpec(512, true))
            .containsEntry("ROLE", new ColumnSpec(20, false))
            .containsEntry("PROVIDER", new ColumnSpec(20, true))
            .containsEntry("STATUS", new ColumnSpec(20, false));
        assertThat(columns.get("WITHDRAWN_AT").nullable()).isTrue();
        assertThat(typeNames("MEMBER")).containsEntry("ROLE", "CHARACTER VARYING").containsEntry("PROVIDER", "CHARACTER VARYING")
            .containsEntry("STATUS", "CHARACTER VARYING");
    }

    @Test
    @DisplayName("member_consent 컬럼이 entity-design §1-2 와 같다")
    void memberConsentColumnsMatchEntityDesign() throws SQLException {
        Map<String, ColumnSpec> columns = columns("MEMBER_CONSENT");

        assertThat(columns.keySet()).containsExactlyInAnyOrder("ID", "MEMBER_ID", "TYPE", "DOCUMENT_VERSION", "AGREED_AT", "WITHDRAWN_AT",
            "CREATED_AT", "UPDATED_AT");
        assertThat(columns).containsEntry("TYPE", new ColumnSpec(30, false))
            .containsEntry("DOCUMENT_VERSION", new ColumnSpec(20, false));
        assertThat(columns.get("MEMBER_ID").nullable()).isFalse();
        assertThat(columns.get("AGREED_AT").nullable()).isFalse();
        assertThat(columns.get("WITHDRAWN_AT").nullable()).isTrue();
        assertThat(typeNames("MEMBER_CONSENT")).containsEntry("TYPE", "CHARACTER VARYING");
    }

    @Test
    @DisplayName("모든 컬럼에 @Comment 가 붙어 있다")
    void everyColumnHasComment() throws SQLException {
        for (String table : List.of("MEMBER", "MEMBER_CONSENT")) {
            try (Connection connection = dataSource.getConnection();
                 ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
                while (rs.next()) {
                    assertThat(rs.getString("REMARKS")).as("%s.%s", table, rs.getString("COLUMN_NAME")).isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("같은 이메일을 두 번 저장하면 unique 위반이 MEMBER_001 도메인 예외로 바뀐다 — 500 이 아니다")
    void duplicateEmailBecomesDomainException() {
        memberRepositoryAdapter.save(member(1L, "dup@example.com"));

        assertThatThrownBy(() -> memberRepositoryAdapter.save(member(2L, "dup@example.com")))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EXIST_MEMBER_EMAIL));
    }

    @Test
    @DisplayName("같은 ID 로 다시 저장하면 기존 행을 덮어쓰지 않고 실패한다 — Persistable 로 merge 대신 persist(INSERT) 를 탄다")
    void sameIdIsNotSilentlyOverwritten() {
        memberRepositoryAdapter.save(member(7L, "first@example.com"));
        testEntityManager.clear();

        assertThatThrownBy(() -> memberRepositoryAdapter.save(member(7L, "second@example.com")))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject("select email from member where id = 7", String.class)).isEqualTo("first@example.com");
    }

    @Test
    @DisplayName("저장한 회원을 이메일로 찾을 수 있고, 감사 컬럼이 채워진다 (JPA Auditing)")
    void savedMemberIsFoundByEmail() {
        Member saved = memberRepositoryAdapter.save(member(3L, "found@example.com"));

        assertThat(saved.id()).isEqualTo(3L);
        assertThat(memberRepositoryAdapter.existsByEmail("found@example.com")).isTrue();
        assertThat(memberRepositoryAdapter.existsByEmail("other@example.com")).isFalse();
    }

    @Test
    @DisplayName("동의 이력은 같은 회원 · 같은 항목으로 여러 행을 쌓을 수 있다 — 재동의는 새 행이다")
    void consentRowsAccumulatePerType() {
        LocalDateTime agreedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        memberConsentRepositoryAdapter.saveAll(List.of(
            consent(10L, ConsentType.SENSITIVE_HEALTH_INFO, "2026-10-01", agreedAt),
            consent(11L, ConsentType.SENSITIVE_HEALTH_INFO, "2026-12-01", agreedAt.plusDays(1))));
        memberConsentRepository.flush();

        assertThat(memberConsentRepository.findAll())
            .extracting(MemberConsentEntity::getMemberId, MemberConsentEntity::getType, MemberConsentEntity::getDocumentVersion)
            .containsExactlyInAnyOrder(
                tuple(99L, ConsentType.SENSITIVE_HEALTH_INFO, "2026-10-01"),
                tuple(99L, ConsentType.SENSITIVE_HEALTH_INFO, "2026-12-01"));
        assertThat(memberConsentRepository.findAll()).allSatisfy(entity -> assertThat(entity.getCreatedAt()).isNotNull());
    }

    private Map<String, List<String>> indexes(String table, boolean unique) throws SQLException {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getIndexInfo(null, null, table, unique, false)) {
            while (rs.next()) {
                if (rs.getBoolean("NON_UNIQUE") == unique || rs.getString("INDEX_NAME") == null) {
                    continue;
                }
                String name = rs.getString("INDEX_NAME");
                // H2 는 PK 인덱스를 PRIMARY_KEY_xx 로 부른다. 이름 대신 컬럼으로 구분할 수 있게 "PK" 로 묶는다.
                String key = name.startsWith("PRIMARY_KEY") ? "PK" : name;
                indexes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
            }
        }
        return indexes;
    }

    private Map<String, ColumnSpec> columns(String table) throws SQLException {
        Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                columns.put(rs.getString("COLUMN_NAME"), new ColumnSpec(rs.getInt("COLUMN_SIZE"), nullable));
            }
        }
        return columns;
    }

    private Map<String, String> typeNames(String table) throws SQLException {
        Map<String, String> types = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                types.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
            }
        }
        return types;
    }

    private static Member member(long id, String email) {
        return Member.builder().id(id).email(email).password("{bcrypt}hash").nickname("닉네임")
            .role(SecurityRole.USER).status(MemberStatus.ACTIVE).build();
    }

    private static MemberConsent consent(long id, ConsentType type, String version, LocalDateTime agreedAt) {
        return MemberConsent.builder().id(id).memberId(99L).type(type).documentVersion(version).agreedAt(agreedAt).build();
    }

    private record ColumnSpec(int size, boolean nullable) {
    }
}
