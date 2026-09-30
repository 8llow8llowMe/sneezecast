package com.sneezecast.security.resourceserver.resolver;

import com.sneezecast.security.common.exception.SecurityErrorCode;
import com.sneezecast.security.common.resolver.JwtTokenErrorResolver;
import java.util.Locale;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

public class OAuth2ResourceTokenErrorResolver implements JwtTokenErrorResolver {

    @Override
    public SecurityErrorCode resolve(Throwable ex) {

        if (ex instanceof OAuth2AuthenticationException oauth2Ex) {
            // OAuth2Error.description 은 null 허용 필드다 — NPE 로 인증 오류가 500 이 되지 않게 비운다.
            String description = oauth2Ex.getError().getDescription();
            String msg = description == null ? "" : description.toLowerCase(Locale.ROOT);

            if (msg.contains("expired")) {
                return SecurityErrorCode.TOKEN_EXPIRED;
            }
            if (msg.contains("signature")) {
                return SecurityErrorCode.TOKEN_SIGNATURE_INVALID;
            }
            if (msg.contains("malformed")) {
                return SecurityErrorCode.TOKEN_MALFORMED;
            }
            if (msg.contains("invalid")) {
                return SecurityErrorCode.TOKEN_INVALID;
            }
        }

        return SecurityErrorCode.UNAUTHORIZED;
    }
}
