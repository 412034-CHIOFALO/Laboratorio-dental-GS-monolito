package com.gs.monolito.auth.service;

import com.gs.monolito.auth.model.AuditoriaEvento;
import com.gs.monolito.auth.repository.AuditoriaEventoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Bitácora de auditoría del sistema. Antes recibía eventos de los otros
 * microservicios vía HTTP (POST /api/auth/auditoria/ingest, autenticado con
 * X-Internal-Key); en el monolito los módulos futuros (catalogo/pedidos/
 * finanzas/stock) llaman este {@code registrar(...)} directo, en el mismo
 * proceso — el endpoint HTTP de ingesta y su filtro de autenticación por key
 * quedaron sin uso y se eliminaron (ver AuthSecurityConfig).
 */
@Service
public class AuditoriaService {

    private final AuditoriaEventoRepository repo;

    public AuditoriaService(AuditoriaEventoRepository repo) {
        this.repo = repo;
    }

    /**
     * Registra un evento. Los textos se cortan al largo de su columna: la auditoría nunca debe hacer fallar la
     * operación de negocio que está anotando (un nombre largo o un detalle de más de 500 caracteres reventaría el
     * guardado y, al estar en la misma transacción, revertiría el pago o el pedido).
     */
    public void registrar(String usuario, String tipo, String accion, String entidad, String detalle) {
        repo.save(new AuditoriaEvento(
                cortar(usuario, 100), cortar(tipo, 50), cortar(accion, 200), cortar(entidad, 200), cortar(detalle, 500)));
    }

    private static String cortar(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    /** tipo/q nulos o en blanco no filtran nada — ver AuditoriaEventoRepository#buscar. */
    public Page<AuditoriaEvento> buscar(String tipo, String q, Pageable pageable) {
        String tipoNorm = (tipo == null || tipo.isBlank()) ? null : tipo;
        String qNorm = (q == null || q.isBlank()) ? null : q.toLowerCase();
        return repo.buscar(tipoNorm, qNorm, pageable);
    }
}
