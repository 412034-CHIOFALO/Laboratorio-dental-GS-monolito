package com.gs.monolito.catalogo.config;

import com.gs.monolito.catalogo.model.Categoria;
import com.gs.monolito.catalogo.model.TipoTrabajo;
import com.gs.monolito.catalogo.repository.TipoTrabajoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carga el catálogo base de trabajos dentales en el perfil "dev".
 *
 * IDs generados (auto-increment desde 1):
 *   1 — Corona Metal-Cerámica
 *   2 — Corona Porcelana Pura
 *   3 — Incrustación Onlay
 *   4 — Prótesis Total Superior
 *   5 — Aparato Funcional
 *   6 — Férula Miorelajante ATM
 *
 * Estos IDs son referenciados en el seed dev de pedidos (Etapa 5).
 */
@Component
@Profile("dev")
public class CatalogoDevDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogoDevDataInitializer.class);
    private final TipoTrabajoRepository repository;

    public CatalogoDevDataInitializer(TipoTrabajoRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            log.info("[GS-DEV] Catálogo ya tiene datos — se omite la carga inicial.");
            return;
        }

        List<TipoTrabajo> trabajos = List.of(

            TipoTrabajo.builder()
                .nombre("Corona Metal-Cerámica")
                .descripcion("Corona con núcleo de metal y recubrimiento cerámico. " +
                             "Alta resistencia mecánica, estética aceptable.")
                .precio(new BigDecimal("15000.00"))
                .categoria(Categoria.FIJA)
                .tiempoEstimadoDias(5)
                .activo(true)
                .build(),

            TipoTrabajo.builder()
                .nombre("Corona Porcelana Pura")
                .descripcion("Corona de cerámica sin metal. Máxima estética, " +
                             "ideal para zona anterior. Translucidez natural.")
                .precio(new BigDecimal("22000.00"))
                .categoria(Categoria.FIJA)
                .tiempoEstimadoDias(7)
                .activo(true)
                .build(),

            TipoTrabajo.builder()
                .nombre("Incrustación Onlay")
                .descripcion("Restauración indirecta de resina o cerámica. " +
                             "Cubre una o más cúspides preservando estructura dentaria sana.")
                .precio(new BigDecimal("8000.00"))
                .categoria(Categoria.FIJA)
                .tiempoEstimadoDias(3)
                .activo(true)
                .build(),

            TipoTrabajo.builder()
                .nombre("Prótesis Total Superior")
                .descripcion("Prótesis completa acrílica para arcada superior. " +
                             "Incluye modelo de estudio y prueba en cera.")
                .precio(new BigDecimal("45000.00"))
                .categoria(Categoria.REMOVIBLE)
                .tiempoEstimadoDias(15)
                .activo(true)
                .build(),

            TipoTrabajo.builder()
                .nombre("Aparato Funcional Bimler")
                .descripcion("Aparato ortopédico funcional para corrección de " +
                             "relación esquelética clase II. Acrílico termoplástico.")
                .precio(new BigDecimal("18000.00"))
                .categoria(Categoria.ORTODONCIA)
                .tiempoEstimadoDias(10)
                .activo(true)
                .build(),

            TipoTrabajo.builder()
                .nombre("Férula Miorelajante ATM")
                .descripcion("Placa de acrílico duro de cobertura total para " +
                             "tratamiento de bruxismo y disfunción temporomandibular.")
                .precio(new BigDecimal("12000.00"))
                .categoria(Categoria.ATM)
                .tiempoEstimadoDias(7)
                .activo(true)
                .build()
        );

        repository.saveAll(trabajos);
        log.info("[GS-DEV] {} tipos de trabajo cargados en el catálogo.", trabajos.size());
    }
}
