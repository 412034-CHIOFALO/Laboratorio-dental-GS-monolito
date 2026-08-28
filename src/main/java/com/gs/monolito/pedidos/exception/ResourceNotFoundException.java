package com.gs.monolito.pedidos.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String recurso, Object id) {
        super(recurso + " no encontrado con id: " + id);
    }
    public ResourceNotFoundException(String mensaje) {
        super(mensaje);
    }
}
