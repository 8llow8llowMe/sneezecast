package com.sneezecast.security.common.resolver;

import com.sneezecast.security.common.exception.SecurityErrorCode;

public interface JwtTokenErrorResolver {

    SecurityErrorCode resolve(Throwable ex);
}
