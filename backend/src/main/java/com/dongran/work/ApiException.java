package com.dongran.work;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public ApiException(HttpStatus status, String code, String message) { super(message); this.status=status; this.code=code; }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",message); }
    public static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND",message); }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"CONFLICT",message); }
    public static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN",message); }
}
