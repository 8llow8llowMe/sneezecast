package com.sneezecast.persistence.entity;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate 가 준비하는 SQL 을 그대로 모은다 — {@code hibernate.session_factory.statement_inspector} 로 건다. Hibernate 가 기본 생성자로 만들어
 * 인스턴스를 테스트에서 꺼낼 수 없으므로 정적 목록에 쌓는다. 테스트는 측정 직전에 {@link #clear()} 한다.
 */
public class SqlCaptor implements StatementInspector {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql.trim().toLowerCase(Locale.ROOT));
        return sql;
    }

    static void clear() {
        STATEMENTS.clear();
    }

    static List<String> statements() {
        return List.copyOf(STATEMENTS);
    }
}
