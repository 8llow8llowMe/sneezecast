package com.sneezecast.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.Comment;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public class BaseEntity {

    @CreatedDate
    @Comment("생성 날짜")
    @Column(updatable = false, nullable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;


    @LastModifiedDate
    @Comment("수정 날짜")
    @Column(nullable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;
}
