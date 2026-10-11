package com.sneezecast.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.persistence.config.JpaAuditConfig;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
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
 * {@link BaseEntity} 의 새 행 판정(coding-conventions §8-1). ID 를 미리 정하는 엔티티도 {@code save} 가 merge 가 아니라 persist 를 타서, INSERT 전에
 * SELECT 가 나가지 않고 겹치는 ID 는 기존 행을 덮어쓰지 않고 실패해야 한다. 기존 행은 조회한 엔티티의 변경 감지로 고친다.
 */
@DataJpaTest
@Import(JpaAuditConfig.class)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.sneezecast.persistence.entity.SqlCaptor"
})
class BaseEntityPersistableTest {

    @Autowired
    private AssignedIdEntityRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearCapturedSql() {
        SqlCaptor.clear();
    }

    @Test
    @DisplayName("감사 컬럼 createdAt 이 비어 있으면 새 행이고, 저장하면 Auditing 이 채워 기존 행이 된다")
    void isNewFollowsCreatedAt() {
        AssignedIdEntity entity = new AssignedIdEntity(1L, "first");
        assertThat(entity.isNew()).isTrue();

        AssignedIdEntity saved = repository.saveAndFlush(entity);

        assertThat(saved).isSameAs(entity);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.isNew()).isFalse();
    }

    @Test
    @DisplayName("ID 를 미리 정한 새 엔티티를 저장하면 SELECT 없이 INSERT 한 번만 나간다 — merge 를 타지 않는다")
    void newEntityIsInsertedWithoutSelect() {
        SqlCaptor.clear();

        repository.saveAndFlush(new AssignedIdEntity(2L, "first"));

        assertThat(SqlCaptor.statements()).hasSize(1).first().asString().startsWith("insert into assigned_id_entity");
    }

    @Test
    @DisplayName("같은 ID 를 새 엔티티로 다시 저장하면 기존 행을 덮어쓰지 않고 PK 위반으로 실패한다")
    void sameIdIsNotSilentlyOverwritten() {
        repository.saveAndFlush(new AssignedIdEntity(3L, "first"));
        testEntityManager.clear();

        assertThatThrownBy(() -> repository.saveAndFlush(new AssignedIdEntity(3L, "second")))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject("select name from assigned_id_entity where id = 3", String.class)).isEqualTo("first");
    }

    @Test
    @DisplayName("조회한 엔티티는 기존 행이다 — 값을 바꾸면 변경 감지로 UPDATE 되고 INSERT 는 나가지 않는다")
    void loadedEntityIsUpdatedByDirtyChecking() {
        repository.saveAndFlush(new AssignedIdEntity(4L, "first"));
        testEntityManager.clear();

        AssignedIdEntity loaded = repository.findById(4L).orElseThrow();
        assertThat(loaded.isNew()).isFalse();
        SqlCaptor.clear();

        loaded.changeName("second");
        repository.flush();

        assertThat(SqlCaptor.statements()).hasSize(1).first().asString().startsWith("update assigned_id_entity");
        testEntityManager.clear();
        assertThat(repository.findById(4L).orElseThrow().getName()).isEqualTo("second");
    }
}
