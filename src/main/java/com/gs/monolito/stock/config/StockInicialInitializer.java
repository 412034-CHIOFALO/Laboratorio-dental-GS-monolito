package com.gs.monolito.stock.config;

import com.gs.monolito.stock.model.CategoriaMaterial;
import com.gs.monolito.stock.model.Material;
import com.gs.monolito.stock.repository.MaterialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carga un set base de materiales de laboratorio dental al arrancar, cubriendo
 * las categorías que ya usa el catálogo. Corre en TODOS los ambientes — en
 * producción el stock arranca vacío y sin esto no hay nada para descontar.
 *
 * <p>Idempotente por nombre, no por conteo de tabla: si el laboratorio ya cargó
 * materiales propios a mano, este seed no los duplica ni los pisa.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockInicialInitializer implements CommandLineRunner {

    private final MaterialRepository repository;

    @Override
    public void run(String... args) {
        List<Material> base = List.of(
            Material.builder().nombre("Yeso Piedra Tipo IV")
                .categoria(CategoriaMaterial.YESO)
                .stockActual(15.0).stockMinimo(5.0).unidadMedida("kg")
                .precioUnitario(new BigDecimal("3500")).descuentaStock(true).build(),
            Material.builder().nombre("Cerámica Feldespática")
                .categoria(CategoriaMaterial.CERAMICA)
                .stockActual(8.0).stockMinimo(3.0).unidadMedida("frasco")
                .precioUnitario(new BigDecimal("8500")).descuentaStock(true).build(),
            Material.builder().nombre("Porcelana Estratificada")
                .categoria(CategoriaMaterial.PORCELANA)
                .stockActual(6.0).stockMinimo(3.0).unidadMedida("frasco")
                .precioUnitario(new BigDecimal("7800")).descuentaStock(true).build(),
            Material.builder().nombre("Acrílico Rosa Termocurable")
                .categoria(CategoriaMaterial.ACRILICO)
                .stockActual(10.0).stockMinimo(4.0).unidadMedida("kg")
                .precioUnitario(new BigDecimal("4200")).descuentaStock(true).build(),
            Material.builder().nombre("Aleación Cromo-Cobalto")
                .categoria(CategoriaMaterial.METAL)
                .stockActual(500.0).stockMinimo(150.0).unidadMedida("g")
                .precioUnitario(new BigDecimal("95")).descuentaStock(true).build(),
            Material.builder().nombre("Resina Autopolimerizable")
                .categoria(CategoriaMaterial.RESINA)
                .stockActual(6.0).stockMinimo(3.0).unidadMedida("kit")
                .precioUnitario(new BigDecimal("6200")).descuentaStock(true).build(),
            Material.builder().nombre("Alambre Inoxidable 0.7mm")
                .categoria(CategoriaMaterial.ALAMBRE)
                .stockActual(10.0).stockMinimo(3.0).unidadMedida("rollo")
                .precioUnitario(new BigDecimal("1800")).descuentaStock(true).build(),
            Material.builder().nombre("Discos de Zirconia")
                .categoria(CategoriaMaterial.ZIRCONIA)
                .stockActual(8.0).stockMinimo(3.0).unidadMedida("unidad")
                .precioUnitario(new BigDecimal("18500")).descuentaStock(true).build(),
            Material.builder().nombre("Cera para Encerado")
                .categoria(CategoriaMaterial.CERA)
                .stockActual(5.0).stockMinimo(2.0).unidadMedida("barra")
                .precioUnitario(new BigDecimal("1500")).descuentaStock(true).build(),
            Material.builder().nombre("Adhesivo Dental")
                .categoria(CategoriaMaterial.ADHESIVO)
                .stockActual(12.0).stockMinimo(4.0).unidadMedida("frasco")
                .precioUnitario(new BigDecimal("2200")).descuentaStock(true).build(),
            Material.builder().nombre("Fresas y Discos de Corte")
                .categoria(CategoriaMaterial.HERRAMIENTA)
                .stockActual(20.0).stockMinimo(6.0).unidadMedida("unidad")
                .precioUnitario(new BigDecimal("900")).descuentaStock(false).build(),
            Material.builder().nombre("Separadores de Goma")
                .categoria(CategoriaMaterial.CONSUMIBLE)
                .stockActual(20.0).stockMinimo(6.0).unidadMedida("bolsa")
                .precioUnitario(new BigDecimal("1200")).descuentaStock(false).build()
        );

        List<Material> faltantes = base.stream()
            .filter(m -> !repository.existsByNombreIgnoreCase(m.getNombre()))
            .toList();

        if (faltantes.isEmpty()) {
            log.info("[GS-STOCK] Set base de materiales ya presente — nada que agregar.");
            return;
        }

        repository.saveAll(faltantes);
        log.info("[GS-STOCK] {} material(es) base agregado(s) al stock: {}",
            faltantes.size(), faltantes.stream().map(Material::getNombre).toList());
    }
}
