package com.gs.monolito.catalogo.exception;

import com.gs.monolito.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maneja las excepciones de dominio propias de catalogo. Las excepciones de
 * framework (validación, 404 genérico, etc.) las cubre
 * {@link com.gs.monolito.common.exception.GlobalExceptionHandler} — scoped acá
 * por basePackages para no competir con los advices de dominio de los otros
 * módulos cuando se porten.
 * {@code @Order} más chico que el de {@code GlobalExceptionHandler}
 * (LOWEST_PRECEDENCE) para que este advice, más específico, se evalúe primero
 * — ver el javadoc de esa clase.
 */
@RestControllerAdvice(basePackages = "com.gs.monolito.catalogo")
@Order(0)
public class CatalogoExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(404, "Not Found", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of(409, "Conflict", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse.of(422, "Unprocessable Entity", ex.getMessage(), req.getRequestURI()));
    }
}
