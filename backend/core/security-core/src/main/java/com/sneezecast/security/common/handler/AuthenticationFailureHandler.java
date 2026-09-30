package com.sneezecast.security.common.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public interface AuthenticationFailureHandler {

    boolean handleAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, Throwable exception) throws IOException;

    boolean supports(Throwable exception);
}
