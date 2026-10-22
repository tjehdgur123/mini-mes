package com.minimes.common;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(String code, String message) {
    }

    @ExceptionHandler(MesException.class)
    public ResponseEntity<ErrorResponse> handleMes(MesException e) {
        log.info("업무 오류 {}: {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", msg));
    }

    /** 동시에 같은 번호를 채번한 경우 등 UNIQUE 충돌 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateKeyException e) {
        log.warn("중복 키 충돌", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_KEY", "동시에 같은 데이터가 등록되었습니다. 다시 시도하세요."));
    }

    /** FOR UPDATE WAIT 시간 안에 잠금을 얻지 못함: 같은 LOT/설비를 다른 요청이 처리 중 */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleLock(PessimisticLockingFailureException e) {
        log.warn("잠금 대기 시간 초과", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("LOCK_TIMEOUT", "다른 작업이 같은 LOT 또는 설비를 처리 중입니다. 잠시 후 다시 시도하세요."));
    }

    /** 애플리케이션 검증을 통과했는데 DB CHECK/FK 제약에 걸린 경우: 최후 방어선이 동작한 것 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException e) {
        log.error("DB 제약조건 위반", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DATA_INTEGRITY", "데이터 제약조건에 위배되어 저장하지 않았습니다."));
    }
}
