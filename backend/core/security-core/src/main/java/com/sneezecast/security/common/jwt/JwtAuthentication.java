package com.sneezecast.security.common.jwt;

import com.sneezecast.security.common.constant.SecurityScope;
import com.sneezecast.security.common.dto.MemberLoginActive;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Getter
public class JwtAuthentication extends AbstractAuthenticationToken {

    private final MemberLoginActive principal;
    private final Object credentials;

    public JwtAuthentication(MemberLoginActive principal, Object credentials,
        Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.credentials = credentials;
        super.setAuthenticated(true);
    }

    /**
     * 인증된 회원으로 인증 주체를 만든다. auth 측 필터와 서비스 측 converter 가 같은 authority 규칙을 쓰도록 한 곳에 둔다.
     *
     * <p>authority 는 역할 이름 그대로(예: {@code OPERATOR}) 하나와 scope 마다 {@code SCOPE_<scope>} 다.
     */
    public static JwtAuthentication authenticated(MemberLoginActive principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(principal.role().name()));
        principal.scopes().stream()
            .sorted()
            .map(scope -> new SimpleGrantedAuthority(SecurityScope.authority(scope)))
            .forEach(authorities::add);

        return new JwtAuthentication(principal, "", authorities);
    }

    @Override
    public Object getCredentials() {
        return this.credentials;
    }

    @Override
    public Object getPrincipal() {
        return this.principal;
    }
}
