package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.mapper.MemberMapperImpl;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.LocalDateTime;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 수정이 <b>실제 {@code @Transactional} 프록시</b>에서 "조회 후 변경 감지" 로 UPDATE 되는지 H2 에서 본다 (coding-conventions §8-1).
 *
 * <p>{@code @DataJpaTest} 의 테스트 트랜잭션을 끈다({@code NOT_SUPPORTED}) — 켜 두면 처리기가 바깥 트랜잭션에 합류해, 커밋으로 실제 UPDATE 가 나가는지와
 * 트랜잭션 없는 호출이 막히는지를 볼 수 없다. 대신 테스트마다 테이블을 비운다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({JpaAuditConfig.class, MemberCommandProcessor.class, MemberRepositoryAdapter.class, MemberMapperImpl.class})
class MemberCommandTransactionTest {

    @Autowired
    private MemberCommandProcessor processor;

    @Autowired
    private MemberRepositoryAdapter memberRepositoryAdapter;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("닉네임 · 비밀번호 수정은 기존 행을 UPDATE 한다 — 새 행 · PK 위반이 없고, 다른 컬럼은 그대로이며 수정 시각이 갱신된다")
    void updatesExistingRowByDirtyChecking() {
        memberRepositoryAdapter.save(member(42L));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LocalDateTime createdUpdatedAt = jdbc.queryForObject("select updated_at from member where id = 42", LocalDateTime.class);

        Member renamed = processor.changeNickname(42L, "새닉네임");
        processor.changePassword(42L, "$2a$10$newHash");

        assertThat(renamed.nickname()).isEqualTo("새닉네임");
        assertThat(jdbc.queryForObject("select count(*) from member", Integer.class)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select email, nickname, password, status from member where id = 42");
        assertThat(row).containsEntry("EMAIL", "user@example.com").containsEntry("NICKNAME", "새닉네임").containsEntry("PASSWORD", "$2a$10$newHash")
            .containsEntry("STATUS", "ACTIVE");
        assertThat(jdbc.queryForObject("select updated_at from member where id = 42", LocalDateTime.class)).isAfterOrEqualTo(createdUpdatedAt);
    }

    @Test
    @DisplayName("프로필 이미지 key 를 바꾸면 소셜 URL 은 비워지고 이전 key 가 돌아온다 — 지우면 둘 다 비고, 다시 지워도 이전 key 없이 성공한다(멱등)")
    void changesAndRemovesProfileImage() {
        memberRepositoryAdapter.save(member(42L, MemberStatus.ACTIVE, "https://k.kakaocdn.net/profile.jpg"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(processor.changeProfileImage(42L, "members/profiles/42/2026/10/a.png")).as("소셜 URL 만 있었으면 지울 key 가 없다").isEmpty();
        assertThat(jdbc.queryForMap("select profile_image_key, profile_image_url from member where id = 42"))
            .containsEntry("PROFILE_IMAGE_KEY", "members/profiles/42/2026/10/a.png").containsEntry("PROFILE_IMAGE_URL", null);

        assertThat(processor.changeProfileImage(42L, "members/profiles/42/2026/10/b.png")).contains("members/profiles/42/2026/10/a.png");
        assertThat(processor.removeProfileImage(42L)).contains("members/profiles/42/2026/10/b.png");
        assertThat(jdbc.queryForMap("select profile_image_key, profile_image_url from member where id = 42"))
            .containsEntry("PROFILE_IMAGE_KEY", null).containsEntry("PROFILE_IMAGE_URL", null);
        assertThat(processor.removeProfileImage(42L)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from member", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴한 회원의 프로필 이미지는 바꾸거나 지울 수 없다(MEMBER_002) — 잠금 읽기로 본 상태로 판정한다")
    void withdrawnMemberCannotChangeProfileImage() {
        memberRepositoryAdapter.save(member(42L, MemberStatus.WITHDRAWN, null));

        assertThatThrownBy(() -> processor.changeProfileImage(42L, "members/profiles/42/2026/10/a.png"))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER));
        assertThatThrownBy(() -> processor.removeProfileImage(42L))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER));
        assertThat(new JdbcTemplate(dataSource).queryForObject("select profile_image_key from member where id = 42", String.class)).isNull();
    }

    @Test
    @DisplayName("행이 없으면 MEMBER_004 다")
    void missingMemberIsNotFound() {
        assertThatThrownBy(() -> processor.changeNickname(99L, "새닉네임"))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
        assertThatThrownBy(() -> processor.changePassword(99L, "$2a$10$newHash"))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    @DisplayName("트랜잭션 없이 저장소 수정을 부르면 바로 실패한다 — 분리된 엔티티의 변경이 조용히 사라지지 않게(MANDATORY)")
    void adapterUpdateRequiresTransaction() {
        memberRepositoryAdapter.save(member(42L));

        assertThatThrownBy(() -> memberRepositoryAdapter.updateNickname(42L, "새닉네임")).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> memberRepositoryAdapter.updatePassword(42L, "$2a$10$newHash")).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> memberRepositoryAdapter.updateProfileImage(42L, "members/profiles/42/2026/10/a.png"))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject("select nickname from member where id = 42", String.class)).isEqualTo("닉네임");
    }

    private static Member member(long id) {
        return member(id, MemberStatus.ACTIVE, null);
    }

    private static Member member(long id, MemberStatus status, String profileImageUrl) {
        return Member.builder().id(id).email("user@example.com").password("$2a$10$oldHash").nickname("닉네임").profileImageUrl(profileImageUrl)
            .role(SecurityRole.USER).status(status).build();
    }
}
