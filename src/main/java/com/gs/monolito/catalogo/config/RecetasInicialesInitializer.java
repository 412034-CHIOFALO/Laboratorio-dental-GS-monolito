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
            METAL = 5, RESINA = 6, ALAMBRE = 7, ZIRCONIA = 8, CERA = 9, ADHESIVO = 10;

    private record Linea(long materialId, String materialNombre, String unidad, String cantidad) {}

    @Override
    @Transactional
    public void run(String... args) {
        int completados = 0;

        completados += aplicar("Corona de Zirconio",
            linea(ZIRCONIA, "Discos de Zirconia", "unidad", "1"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.1"));

        completados += aplicar("Corona de Porcelana sobre Metal",
            linea(METAL, "Aleación Cromo-Cobalto", "g", "15"),
            linea(PORCELANA, "Porcelana Estratificada", "frasco", "0.05"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.1"));

        completados += aplicar("Puente de Porcelana (3 piezas)",
            linea(METAL, "Aleación Cromo-Cobalto", "g", "35"),
            linea(PORCELANA, "Porcelana Estratificada", "frasco", "0.1"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.15"));

        completados += aplicar("Carilla de Porcelana",
            linea(CERAMICA, "Cerámica Feldespática", "frasco", "0.03"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.05"));

        completados += aplicar("Perno Muñón Colado",
            linea(METAL, "Aleación Cromo-Cobalto", "g", "8"),
            linea(CERA, "Cera para Encerado", "barra", "0.1"));

        completados += aplicar("Prótesis Completa Superior",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.3"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.4"),
            linea(CERA, "Cera para Encerado", "barra", "0.2"));

        completados += aplicar("Prótesis Completa Inferior",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.3"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.4"),
            linea(CERA, "Cera para Encerado", "barra", "0.2"));

        completados += aplicar("Prótesis Parcial Metálica",
            linea(METAL, "Aleación Cromo-Cobalto", "g", "25"),
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.1"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.2"));

        completados += aplicar("Prótesis Parcial Acrílica",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.15"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.2"));

        completados += aplicar("Reparación de Prótesis",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.05"));

        completados += aplicar("Placa de Ortodoncia Removible",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.08"),
            linea(ALAMBRE, "Alambre Inoxidable 0.7mm", "rollo", "0.3"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.15"));

        completados += aplicar("Retenedor de Hawley",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.05"),
            linea(ALAMBRE, "Alambre Inoxidable 0.7mm", "rollo", "0.2"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.1"));

        completados += aplicar("Retenedor Fijo Lingual",
            linea(ALAMBRE, "Alambre Inoxidable 0.7mm", "rollo", "0.15"),
            linea(ADHESIVO, "Adhesivo Dental", "frasco", "0.02"));

        completados += aplicar("Aparato de Expansión Palatina",
            linea(METAL, "Aleación Cromo-Cobalto", "g", "20"),
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.05"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.15"));

        completados += aplicar("Placa Miorelajante",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.1"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.15"));

        completados += aplicar("Placa de Reposicionamiento",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.1"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.15"));

        completados += aplicar("Aparato de ATM Completo",
            linea(ACRILICO, "Acrílico Rosa Termocurable", "kg", "0.2"),
            linea(YESO, "Yeso Piedra Tipo IV", "kg", "0.3"),
            linea(ALAMBRE, "Alambre Inoxidable 0.7mm", "rollo", "0.1"));

        // "Trabajo a Medida" queda sin receta a propósito — es genérico, se define caso a caso.

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
