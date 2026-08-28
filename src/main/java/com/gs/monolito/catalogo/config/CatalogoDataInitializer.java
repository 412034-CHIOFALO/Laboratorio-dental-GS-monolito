package com.gs.monolito.catalogo.config;

import com.gs.monolito.catalogo.model.Categoria;
import com.gs.monolito.catalogo.model.TipoTrabajo;
import com.gs.monolito.catalogo.repository.TipoTrabajoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carga datos de ejemplo al inicio de la aplicación.
 * Solo inserta si la tabla está vacía para no duplicar al reiniciar.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogoDataInitializer implements CommandLineRunner {

    private final TipoTrabajoRepository repository;

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            log.info("Catálogo ya inicializado — omitiendo seed ({} registros existentes)", repository.count());
            return;
        }

        log.info("Inicializando catálogo de trabajos con datos de ejemplo...");

        List<TipoTrabajo> trabajos = List.of(

            TipoTrabajo.builder()
                .nombre("Corona de Zirconio")
                .descripcion("Corona unitaria de zirconio de alta resistencia. Excelente estética y biocompatibilidad. Indicada para sectores anteriores y posteriores.")
                .precio(new BigDecimal("185000"))
                .categoria(Categoria.FIJA)
                .tiempoEstimadoDias(7)
                .build(),

            TipoTrabajo.builder()
                .nombre("Corona de Porcelana sobre Metal")
                .descripcion("Corona metal-cerámica de alta durabilidad. Margen metálico con frente cerámico. Indicada para sectores posteriores.")
                .precio(new BigDecimal("120000"))
                .categoria(Categoria.FIJA)
                .tiempoEstimadoDias(6)
                .build(),

            TipoTrabajo.builder()
                .nombre("Puente de Porcelana (3 piezas)")
                .descripcion("Prótesis fija de tres piezas sobre metal. Rehabilitación de un elemento perdido con apoyo en dientes pilares. Incluye impresión, muñones y terminación.")
                .precio(new BigDecimal("320000"))
                .categoria(Categoria.FIJA)
                .tiempoEstimadoDias(10)
                .build(),

            TipoTrabajo.builder()
                .nombre("Carilla de Porcelana")
                .descripcion("Laminado cerámico ultrafino para mejora estética del sector anterior. Mínima preparación dentaria. Trabajo de alta precisión.")
                .precio(new BigDecimal("145000"))
                .categoria(Categoria.FIJA)
                .tiempoEstimadoDias(8)
                .build(),

            TipoTrabajo.builder()
                .nombre("Perno Muñón Colado")
                .descripcion("Perno muñón de cromo-cobalto fundido a medida. Reconstrucción de dientes con poca estructura coronaria remanente.")
                .precio(new BigDecimal("65000"))
                .categoria(Categoria.FIJA)
                .tiempoEstimadoDias(4)
                .build(),

            TipoTrabajo.builder()
                .nombre("Prótesis Completa Superior")
                .descripcion("Dentadura completa acrílica superior. Dientes de acrílico de alta resistencia. Incluye oclusión, articulación y terminado.")
                .precio(new BigDecimal("95000"))
                .categoria(Categoria.REMOVIBLE)
                .tiempoEstimadoDias(14)
                .build(),

            TipoTrabajo.builder()
                .nombre("Prótesis Completa Inferior")
                .descripcion("Dentadura completa acrílica inferior. Especial atención a la retención y estabilidad en maxilar inferior.")
                .precio(new BigDecimal("95000"))
                .categoria(Categoria.REMOVIBLE)
                .tiempoEstimadoDias(14)
                .build(),

            TipoTrabajo.builder()
                .nombre("Prótesis Parcial Metálica")
                .descripcion("Prótesis esquelética de cromo-cobalto con retenedores colados. Alta resistencia y menor volumen que la acrílica.")
                .precio(new BigDecimal("175000"))
                .categoria(Categoria.REMOVIBLE)
                .tiempoEstimadoDias(12)
                .build(),

            TipoTrabajo.builder()
                .nombre("Prótesis Parcial Acrílica")
                .descripcion("Prótesis parcial removible en acrílico convencional. Solución provisional o definitiva de bajo costo para sectores parciales.")
                .precio(new BigDecimal("60000"))
                .categoria(Categoria.REMOVIBLE)
                .tiempoEstimadoDias(7)
                .build(),

            TipoTrabajo.builder()
                .nombre("Reparación de Prótesis")
                .descripcion("Reparación de fracturas, adición de dientes o rebase de prótesis existente. Precio variable según complejidad.")
                .precio(BigDecimal.ZERO)
                .categoria(Categoria.REMOVIBLE)
                .tiempoEstimadoDias(2)
                .build(),

            TipoTrabajo.builder()
                .nombre("Placa de Ortodoncia Removible")
                .descripcion("Aparato de acrílico con resortes y tornillos de expansión. Tratamiento de ortodoncia interceptiva en dentición mixta.")
                .precio(new BigDecimal("85000"))
                .categoria(Categoria.ORTODONCIA)
                .tiempoEstimadoDias(5)
                .build(),

            TipoTrabajo.builder()
                .nombre("Retenedor de Hawley")
                .descripcion("Retenedor postortodóncico acrílico con arco vestibular. Mantiene la posición dental lograda al final del tratamiento.")
                .precio(new BigDecimal("55000"))
                .categoria(Categoria.ORTODONCIA)
                .tiempoEstimadoDias(3)
                .build(),

            TipoTrabajo.builder()
                .nombre("Retenedor Fijo Lingual")
                .descripcion("Barra de alambre pegada en la cara lingual de los incisivos. Retención permanente e invisible post-tratamiento.")
                .precio(new BigDecimal("45000"))
                .categoria(Categoria.ORTODONCIA)
                .tiempoEstimadoDias(2)
                .build(),

            TipoTrabajo.builder()
                .nombre("Aparato de Expansión Palatina")
                .descripcion("Disyuntor palatino de cromo-cobalto con tornillo de expansión. Tratamiento de compresión maxilar en pacientes en crecimiento.")
                .precio(new BigDecimal("135000"))
                .categoria(Categoria.ORTODONCIA)
                .tiempoEstimadoDias(6)
                .build(),

            TipoTrabajo.builder()
                .nombre("Placa Miorelajante")
                .descripcion("Placa oclusal de acrílico duro de cobertura total. Tratamiento del bruxismo y disfunciones de la articulación temporomandibular.")
                .precio(new BigDecimal("110000"))
                .categoria(Categoria.ATM)
                .tiempoEstimadoDias(4)
                .build(),

            TipoTrabajo.builder()
                .nombre("Placa de Reposicionamiento")
                .descripcion("Placa anterior de reposicionamiento condilar. Tratamiento de luxaciones y desplazamientos discales de la ATM.")
                .precio(new BigDecimal("125000"))
                .categoria(Categoria.ATM)
                .tiempoEstimadoDias(5)
                .build(),

            TipoTrabajo.builder()
                .nombre("Aparato de ATM Completo")
                .descripcion("Sistema completo de aparatología para disfunción temporomandibular. Incluye placas superior e inferior con seguimiento de ajustes.")
                .precio(new BigDecimal("210000"))
                .categoria(Categoria.ATM)
                .tiempoEstimadoDias(8)
                .build(),

            TipoTrabajo.builder()
                .nombre("Trabajo a Medida")
                .descripcion("Trabajo dental personalizado según indicación específica del odontólogo. Precio y tiempo estimado a convenir en cada caso.")
                .precio(BigDecimal.ZERO)
                .categoria(Categoria.PERSONALIZADO)
                .tiempoEstimadoDias(0)
                .build()
        );

        repository.saveAll(trabajos);
        log.info("✓ Catálogo inicializado con {} tipos de trabajo.", trabajos.size());
    }
}
