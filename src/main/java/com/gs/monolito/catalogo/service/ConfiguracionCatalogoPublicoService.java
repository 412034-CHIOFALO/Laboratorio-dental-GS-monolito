package com.gs.monolito.catalogo.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.catalogo.dto.ConfiguracionCatalogoPublicoResponse;
import com.gs.monolito.catalogo.model.ConfiguracionCatalogoPublico;
import com.gs.monolito.catalogo.repository.ConfiguracionCatalogoPublicoRepository;
import com.gs.monolito.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lee/actualiza el toggle único de {@link ConfiguracionCatalogoPublico}
 * (id=1 fijo). La migración V2 ya siembra esa fila, pero si por algún
 * motivo faltara (base restaurada de un backup viejo, etc.) se crea acá con
 * {@code habilitado=false} en vez de tirar 404/500.
 * <p>
 * Todos los métodos son de lectoescritura (no {@code readOnly=true}) a
 * propósito: el fallback de "crear si falta" puede necesitar escribir en
 * cualquiera de ellos, y por ser un self-invocation (todo dentro de la misma
 * clase) el proxy de Spring no puede aplicar un nivel de transacción
 * distinto por método igual — más simple mantenerlos todos iguales que
 * confiar en una anotación que en la práctica no se respeta.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ConfiguracionCatalogoPublicoService {

    private final ConfiguracionCatalogoPublicoRepository repository;
    private final AuditoriaService auditoria;

    public boolean estaHabilitado() {
        return obtenerOCrear().isHabilitado();
    }

    public ConfiguracionCatalogoPublicoResponse obtener() {
        return ConfiguracionCatalogoPublicoResponse.from(obtenerOCrear());
    }

    public ConfiguracionCatalogoPublicoResponse actualizar(boolean habilitado) {
        ConfiguracionCatalogoPublico config = obtenerOCrear();
        config.setHabilitado(habilitado);
        ConfiguracionCatalogoPublicoResponse resp = ConfiguracionCatalogoPublicoResponse.from(repository.save(config));
        auditoria.registrar(CurrentUser.usernameOrSistema(), "EDITAR", "Catálogo público " + (habilitado ? "habilitado" : "deshabilitado"),
                "Configuración", "");
        return resp;
    }

    private ConfiguracionCatalogoPublico obtenerOCrear() {
        return repository.findById(ConfiguracionCatalogoPublico.ID_UNICO)
                .orElseGet(() -> repository.save(
                        ConfiguracionCatalogoPublico.builder()
                                .id(ConfiguracionCatalogoPublico.ID_UNICO)
                                .habilitado(false)
                                .build()));
    }
}
