package com.gs.monolito.auth.service;

import com.gs.monolito.auth.model.AuditoriaEvento;
import com.gs.monolito.auth.repository.AuditoriaEventoRepository;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public void registrar(String usuario, String tipo, String accion, String entidad, String detalle) {
        repo.save(new AuditoriaEvento(usuario, tipo, accion, entidad, detalle));
    }

    public List<AuditoriaEvento> listarTodos() {
        return repo.findAllByOrderByTimestampDesc();
    }
}
