package com.sneezecast.security.common.handler;

import com.sneezecast.security.common.exception.SecurityErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public interface SecurityErrorResponseWriter {

    void write(HttpServletResponse response, SecurityErrorCode errorCode, String detail) throws IOException;
}
