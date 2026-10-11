package com.sneezecast.domainlayer.member.adapter.out.persistence.entity;

import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.persistence.entity.BaseEntity;
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
 * 회원 동의 이력 (entity-design §1-2). 회원당 항목당 여러 행이 쌓인다(문서 개정 시 재동의, 철회 후 재동의) — 그래서
 * {@code (member_id, type)} 에 unique 를 걸지 않는다. 회원은 raw FK 로만 참조하고 DB FK 제약을 두지 않는다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "member_consent",
    indexes = {
        // 항목별 최신 동의 조회 (현재 유효한 동의 = 항목별 agreed_at 최대 행)
        @Index(name = "idx_member_consent_member_id_type", columnList = "memberId, type")
    })
public class MemberConsentEntity extends BaseEntity {

    @Id
    @Comment("동의 이력 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false)
    @Comment("회원 아이디 (FK: member.id)")
    private Long memberId;

    @Column(length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("동의 · 확인 항목 (TERMS_OF_SERVICE/PRIVACY_POLICY/SENSITIVE_HEALTH_INFO/AGE_OVER_19)")
    private ConsentType type;

    @Column(length = 20, nullable = false)
    @Comment("동의한 근거 문서의 버전 - legal.*-version 설정값. AGE_OVER_19 는 이용약관 버전")
    private String documentVersion;

    @Column(nullable = false, columnDefinition = "TIMESTAMP")
    @Comment("동의 시각 - created_at 과 따로 둔다. 감사 대상은 행이 생긴 시각이 아니라 동의한 시각이다")
    private LocalDateTime agreedAt;

    @Column(columnDefinition = "TIMESTAMP")
    @Comment("철회 시각 - 철회 가능한 항목(SENSITIVE_HEALTH_INFO)만 채운다")
    private LocalDateTime withdrawnAt;

    /** 조회한 엔티티에 철회 시각을 채운다 — 트랜잭션 안에서 변경 감지로 UPDATE 된다. 동의 내용(버전 · 동의 시각)은 고치지 않는다. */
    public void withdraw(LocalDateTime withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }
}
