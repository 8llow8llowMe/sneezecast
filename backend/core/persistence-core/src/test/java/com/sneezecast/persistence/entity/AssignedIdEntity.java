package com.sneezecast.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 서비스 엔티티처럼 ID 를 미리 정해(Snowflake 할당) 저장하는 테스트 전용 엔티티. ID 는 생성 전략 없이 할당한다. */
@Entity
@Table(name = "assigned_id_entity")
class AssignedIdEntity extends BaseEntity {

    @Id
    private Long id;

    @Column(nullable = false, length = 30)
    private String name;

    protected AssignedIdEntity() {
    }

    AssignedIdEntity(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public Long getId() {
        return id;
    }

    String getName() {
        return name;
    }

    void changeName(String name) {
        this.name = name;
    }
}
