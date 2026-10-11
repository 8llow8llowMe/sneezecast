package com.sneezecast.domainlayer.member.adapter.out.persistence.entity;

import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.persistence.entity.BaseEntity;
import com.sneezecast.security.common.enums.SecurityRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 회원 (entity-design §1-1). 성명 컬럼을 두지 않는다.
 *
 * <p>enum 컬럼에 {@code @JdbcTypeCode(SqlTypes.VARCHAR)} 를 함께 건다. Hibernate 6 은 {@code @Enumerated(STRING)} 만 있으면 MySQL ·
 * H2 에서 네이티브 {@code enum(...)} 타입을 만든다 — 그러면 값을 하나 늘릴 때마다 ALTER 가 필요하고, dev 의 {@code ddl-auto: update}
 * 는 기존 컬럼 정의를 바꾸지 않아 새 값 INSERT 가 실패한다. entity-design §0 규칙(VARCHAR + 이름 저장)을 지키려면 명시해야 한다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "member",
    indexes = {
        // 동시 가입 요청에서 중복 계정이 생기지 않게 DB 가 막는다. 위반은 MemberRepositoryAdapter 가 도메인 예외로 바꾼다.
        @Index(name = MemberEntity.EMAIL_UNIQUE_INDEX, columnList = "email", unique = true)
    })
public class MemberEntity extends BaseEntity {

    public static final String EMAIL_UNIQUE_INDEX = "uk_member_email";

    @Id
    @Comment("회원 아이디 (Snowflake)")
    private Long id;

    @Comment("이메일 (소문자 정규화)")
    @Column(length = 100, nullable = false)
    private String email;

    @Comment("비밀번호 해시 (BCrypt). 카카오로만 로그인하는 회원은 null")
    @Column(length = 80)
    private String password;

    @Comment("닉네임")
    @Column(length = 30, nullable = false)
    private String nickname;

    @Comment("소셜 제공자가 준 외부 프로필 이미지 URL (직접 업로드하면 null)")
    @Column(length = 500)
    private String profileImageUrl;

    @Comment("직접 업로드한 프로필 이미지 오브젝트 키 (URL 이 아니라 키를 저장한다)")
    @Column(length = 512)
    private String profileImageKey;

    @Comment("역할 (USER/OPERATOR/ADMIN) - JWT role claim 과 같은 값")
    @Column(length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private SecurityRole role;

    @Comment("소셜 로그인 제공자 (KAKAO) - 그 제공자로 로그인할 수 있다. null 이면 이메일 계정만. 비밀번호가 있으면 이메일 로그인도 된다")
    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private OAuthProvider provider;

    @Comment("회원 상태 (ACTIVE/WITHDRAWN/SUSPENDED)")
    @Column(length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private MemberStatus status;

    @Comment("탈퇴 시각 - 30일 보존 후 파기 스케줄러의 판정 기준 (ACTIVE 회원은 null)")
    @Column(columnDefinition = "TIMESTAMP")
    private LocalDateTime withdrawnAt;

    /** 조회한 엔티티의 닉네임을 바꾼다 — 트랜잭션 안에서 변경 감지로 UPDATE 된다 ({@link BaseEntity#isNew()} 주의 참고). */
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 조회한 엔티티에 소셜 로그인 제공자를 연결한다 — 트랜잭션 안에서 변경 감지로 UPDATE 된다. 비밀번호는 그대로 둔다. */
    public void changeProvider(OAuthProvider provider) {
        this.provider = provider;
    }

    /** 조회한 엔티티의 비밀번호 해시를 바꾼다 — 트랜잭션 안에서 변경 감지로 UPDATE 된다. 해시만 받는다(원문 금지). */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    /**
     * 조회한 엔티티를 탈퇴 상태로 바꾼다 — 트랜잭션 안에서 변경 감지로 UPDATE 된다 (entity-design §1-1). 이메일 원문은 다이제스트로 바꾸고 비밀번호 ·
     * 소셜 제공자 · 프로필 이미지를 비우며, 닉네임(not null)은 고정 마스킹 값으로 바꾼다. 행은 보고 파기가 끝날 때까지 남는다(회원 파기는 #244).
     *
     * @param emailDigest    {@code WithdrawnEmailHasher} 가 만든 hex 64자
     * @param maskedNickname 닉네임 자리에 둘 고정 값
     * @param withdrawnAt    탈퇴 시각 — 회원 파기의 판정 기준
     */
    public void withdraw(String emailDigest, String maskedNickname, LocalDateTime withdrawnAt) {
        this.email = emailDigest;
        this.password = null;
        this.nickname = maskedNickname;
        this.profileImageUrl = null;
        this.profileImageKey = null;
        this.provider = null;
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = withdrawnAt;
    }
}
