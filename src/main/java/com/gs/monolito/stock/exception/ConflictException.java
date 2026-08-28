package com.gs.monolito.stock.exception;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
