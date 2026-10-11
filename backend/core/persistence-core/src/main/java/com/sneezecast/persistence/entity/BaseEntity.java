package com.sneezecast.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.Comment;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 감사 컬럼({@code createdAt} · {@code updatedAt})과 새 행 판정을 모든 엔티티에 준다 (coding-conventions §8-1). 하위 엔티티는 {@code Long} ID 의
 * {@link #getId()} 만 제공한다 — 클래스에 {@code @Getter} 를 걸면 Lombok 이 만든다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity implements Persistable<Long> {

    @CreatedDate
    @Comment("생성 날짜")
    @Column(updatable = false, nullable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;


    @LastModifiedDate
    @Comment("수정 날짜")
    @Column(nullable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    /**
     * 새 행인지 판정한다 — {@code createdAt} 은 persist 때 Auditing 이 채우므로, 비어 있으면 아직 저장되지 않은 엔티티다.
     *
     * <p>Snowflake 로 ID 를 미리 정해 두기 때문에 Spring Data 기본 판정(ID null 여부)은 항상 "기존 행" 이 되어 {@code save} 가 merge 로 간다. 그러면
     * INSERT 전에 SELECT 가 한 번 더 나가고, ID 가 겹치면 기존 행을 조용히 덮어쓴다. 이 판정으로 persist 를 타게 해서 겹치는 ID 는 PK 위반으로
     * 실패하게 한다.
     *
     * <p><b>주의</b>: 도메인 모델에서 새로 매핑한 엔티티는 {@code createdAt} 이 비어 있어 항상 새 행으로 본다. 기존 행을 고치는 흐름은 엔티티를
     * 조회해서 바꾸거나(변경 감지) 갱신 쿼리를 써야 한다 — 매핑한 엔티티를 {@code save} 하면 PK 위반이 난다.
     */
    @Override
    @Transient
    public boolean isNew() {
        return createdAt == null;
    }
}
