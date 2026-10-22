package com.minimes.common;

import org.springframework.http.HttpStatus;

/**
 * 업무 규칙 위반을 표현하는 예외.
 * code 는 화면/설비가 분기 처리할 수 있도록 고정된 문자열을 쓴다.
 */
public class MesException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public MesException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static MesException notFound(String code, String message) {
        return new MesException(HttpStatus.NOT_FOUND, code, message);
    }

    public static MesException conflict(String code, String message) {
        return new MesException(HttpStatus.CONFLICT, code, message);
    }

    public static MesException badRequest(String code, String message) {
        return new MesException(HttpStatus.BAD_REQUEST, code, message);
    }
}
