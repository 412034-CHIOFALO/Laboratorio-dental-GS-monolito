package com.gs.monolito.catalogo.config;

import com.gs.monolito.catalogo.model.IngredienteReceta;
import com.gs.monolito.catalogo.model.TipoTrabajo;
import com.gs.monolito.catalogo.repository.TipoTrabajoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Completa la receta (materiales + cantidad) de los tipos de trabajo que ya
 * están cargados en el catálogo, para que el descuento automático de stock
 * (módulo pedidos → ConsumoStockService, Etapa 5) tenga algo de qué descontar.
 *
 * <h2>Sobre los materialId hardcodeados acá abajo</h2>
 * Los materiales viven en el módulo stock (schema {@code gs_stock}) — se
 * asume que {@code StockInicialInitializer} (Etapa 3) fue lo único que
 * insertó materiales en una tabla vacía, en el orden exacto en que están
 * escritos ahí — por eso quedarían con ID 1 a 12 en ese mismo orden.
 *
 * <p><b>Red de seguridad si el ID está mal</b>: cada línea de receta también
 * guarda {@code materialNombre}, y la resolución de material en stock
 * prioriza el nombre sobre el id — así que aunque el ID hardcodeado acá abajo
 * no coincida con la fila real, el descuento igual encuentra el material
 * correcto mientras el nombre sea exactamente el mismo que usa
 * {@code StockInicialInitializer}. El ID solo se usa como último recurso.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecetasInicialesInitializer implements CommandLineRunner {

    private final TipoTrabajoRepository repository;

    private static final long YESO = 1, CERAMICA = 2, PORCELANA = 3, ACRILICO = 4,
            METAL = 5, ALAMBRE = 7, ZIRCONIA = 8, CERA = 9;

    private record Linea(long materialId, String materialNombre, String unidad, String cantidad) {}

    /**
     * Los nombres de acá tienen que ser EXACTAMENTE los 6 tipos de trabajo que
     * carga {@link CatalogoDevDataInitializer} — antes buscaba 17 nombres que
     * no coincidían con ninguno de los 6 reales (residuo de un seed más viejo),
     * así que ninguna receta se cargaba nunca y el descuento automático de
     * stock no tenía nada que descontar en el ambiente de prueba.
     */
    @Override
    @Transactional
    public void run(String... args) {
        int completados = 0;

        completados += aplicar("Corona Metal-Cerámica",
            linea(METAL, "Aleación Cromo-Cobalto", "g", "15"),
            linea(PORCELANA, "Porcelana Estratificada", "frasco", "0.05"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.1"));

        completados += aplicar("Corona Porcelana Pura",
            linea(ZIRCONIA, "Discos de Zirconia", "unidad", "1"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.1"));

        completados += aplicar("Incrustación Onlay",
            linea(CERAMICA, "Cerámica Feldespática", "frasco", "0.03"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.05"));

        completados += aplicar("Prótesis Total Superior",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.3"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.4"),
            linea(CERA, "Cera para Encerado", "barra", "0.2"));

        completados += aplicar("Aparato Funcional Bimler",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.08"),
            linea(ALAMBRE, "Alambre Inoxidable 0.7mm", "rollo", "0.3"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.15"));

        completados += aplicar("Férula Miorelajante ATM",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.1"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.15"));

        if (completados == 0) {
            log.info("[GS-CATALOGO] Recetas ya cargadas — nada que completar.");
        } else {
            log.info("[GS-CATALOGO] Receta completada en {} tipo(s) de trabajo.", completados);
        }
    }

    private Linea linea(long materialId, String nombre, String unidad, String cantidad) {
        return new Linea(materialId, nombre, unidad, cantidad);
    }

    private int aplicar(String nombreTrabajo, Linea... lineas) {
        Optional<TipoTrabajo> opt = repository.findByNombreIgnoreCase(nombreTrabajo);
        if (opt.isEmpty()) {
            log.warn("[GS-CATALOGO] No se encontró el tipo de trabajo \"{}\" — se omite su receta.", nombreTrabajo);
            return 0;
        }
        TipoTrabajo t = opt.get();
        if (!t.getReceta().isEmpty()) return 0;

        t.reemplazarReceta(List.of(lineas).stream()
            .map(l -> IngredienteReceta.builder()
                .materialId(l.materialId())
                .materialNombre(l.materialNombre())
                .unidad(l.unidad())
                .cantidad(new BigDecimal(l.cantidad()))
                .build())
            .toList());
        repository.save(t);
        return 1;
    }
}
