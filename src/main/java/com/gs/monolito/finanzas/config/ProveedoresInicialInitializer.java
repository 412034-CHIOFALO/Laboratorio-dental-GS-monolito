package com.gs.monolito.finanzas.config;

import com.gs.monolito.finanzas.model.Proveedor;
import com.gs.monolito.finanzas.repository.ProveedorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agrega un set base de proveedores de insumos dentales al arrancar. Sin
 * {@code @Profile}, corre en todos los ambientes porque en producción esta
 * tabla arranca vacía. Idempotente por nombre.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProveedoresInicialInitializer implements CommandLineRunner {

    private final ProveedorRepository repository;

    @Override
    public void run(String... args) {
        List<Proveedor> base = List.of(
            Proveedor.builder()
                .nombre("Distribuidora Dental Central")
                .telefono("351-400-1001")
                .email("ventas@dentalcentral.test")
                .direccion("Av. Colón 1200, Córdoba")
                .build(),
            Proveedor.builder()
                .nombre("Casa Dental Norte")
                .telefono("351-400-1002")
                .email("contacto@casadentalnorte.test")
                .direccion("Bv. San Juan 850, Córdoba")
                .build(),
            Proveedor.builder()
                .nombre("Insumos Odontológicos del Interior")
                .telefono("351-400-1003")
                .email("pedidos@insumosinterior.test")
                .direccion("Rivera Indarte 430, Córdoba")
                .build(),
            Proveedor.builder()
                .nombre("Zirconia y Cerámicas SRL")
                .telefono("351-400-1004")
                .email("info@zirconiaceramicas.test")
                .direccion("Fructuoso Rivera 220, Córdoba")
                .build(),
            Proveedor.builder()
                .nombre("Metales y Aleaciones Dentales")
                .telefono("351-400-1005")
                .email("ventas@metalesdentales.test")
                .direccion("Av. Vélez Sarsfield 1580, Córdoba")
                .build()
        );

        List<Proveedor> faltantes = base.stream()
            .filter(p -> !repository.existsByNombreIgnoreCase(p.getNombre()))
            .toList();

        if (faltantes.isEmpty()) {
            log.info("[GS-FINANZAS] Set base de proveedores ya presente — nada que agregar.");
            return;
        }

        repository.saveAll(faltantes);
        log.info("[GS-FINANZAS] {} proveedor(es) base agregado(s): {}",
            faltantes.size(), faltantes.stream().map(Proveedor::getNombre).toList());
    }
}
