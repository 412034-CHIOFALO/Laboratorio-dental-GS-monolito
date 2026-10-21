package com.gs.monolito.pedidos.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Qué está haciendo el lector de correo, para que una falla no pase en silencio: si la contraseña venció o la casilla
 * no responde, el panel lo muestra en vez de que los mails simplemente dejen de llegar.
 */
@Component
public class EstadoCorreo {

    public record Vista(boolean habilitado, LocalDateTime ultimaLectura, LocalDateTime ultimoExito, String ultimoError,
                        long recibidas, long ignoradas, long fallidas) {}

    private volatile boolean habilitado;
    private volatile LocalDateTime ultimaLectura;
    private volatile LocalDateTime ultimoExito;
    private volatile String ultimoError;
    private final AtomicLong recibidas = new AtomicLong();
    private final AtomicLong ignoradas = new AtomicLong();
    private final AtomicLong fallidas = new AtomicLong();

    public void habilitar() {
        habilitado = true;
    }

    public void exito(int nuevas) {
        ultimaLectura = LocalDateTime.now();
        ultimoExito = ultimaLectura;
        ultimoError = null;
        recibidas.addAndGet(nuevas);
    }

    public void error(String mensaje) {
        ultimaLectura = LocalDateTime.now();
        ultimoError = mensaje;
    }

    public void ignorado() {
        ignoradas.incrementAndGet();
    }

    public void fallido() {
        fallidas.incrementAndGet();
    }

    public Vista vista() {
        return new Vista(habilitado, ultimaLectura, ultimoExito, ultimoError, recibidas.get(), ignoradas.get(), fallidas.get());
    }
}
