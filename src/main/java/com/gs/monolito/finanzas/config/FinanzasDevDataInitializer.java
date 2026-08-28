package com.gs.monolito.finanzas.config;

import com.gs.monolito.finanzas.model.*;
import com.gs.monolito.finanzas.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Carga comprobantes de prueba para los pedidos en estado CONTROL y LISTO,
 * más la configuración de sueldos de los integrantes del laboratorio (los
 * empleadoId coinciden con los usuarios sembrados por
 * {@link com.gs.monolito.auth.config.AuthDevDataInitializer}).
 */
@Component
@Profile("dev")
public class FinanzasDevDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FinanzasDevDataInitializer.class);
    private final ComprobanteRepository repository;
    private final ProveedorRepository proveedorRepository;
    private final DeudaProveedorRepository deudaProveedorRepository;
    private final ConfiguracionSueldoRepository configSueldoRepository;

    public FinanzasDevDataInitializer(ComprobanteRepository repository,
                              ProveedorRepository proveedorRepository,
                              DeudaProveedorRepository deudaProveedorRepository,
                              ConfiguracionSueldoRepository configSueldoRepository) {
        this.repository = repository;
        this.proveedorRepository = proveedorRepository;
        this.deudaProveedorRepository = deudaProveedorRepository;
        this.configSueldoRepository = configSueldoRepository;
    }

    @Override
    public void run(String... args) {
        seedConfiguracionSueldos();

        if (repository.count() > 0) {
            log.info("[GS-DEV] finanzas ya tiene datos — se omite la carga inicial.");
            return;
        }

        LocalDate hoy = LocalDate.now();

        List<Comprobante> comprobantes = List.of(

            Comprobante.builder()
                .nroComprobante("COMP-2025-001")
                .pedidoId(4L)
                .nroPedido("gs-2025-0004")
                .odontologoId(4L)
                .odontologoNombre("Dra. Laura Sánchez")
                .trabajo("Aparato Funcional Bimler")
                .monto(new BigDecimal("18000.00"))
                .estadoPago(EstadoPago.PENDIENTE)
                .fechaEmision(hoy)
                .fechaVencimiento(hoy.plusDays(30))
                .build(),

            Comprobante.builder()
                .nroComprobante("COMP-2025-002")
                .pedidoId(5L)
                .nroPedido("gs-2025-0005")
                .odontologoId(3L)
                .odontologoNombre("Dr. Martín García")
                .trabajo("Férula Miorelajante ATM")
                .monto(new BigDecimal("12000.00"))
                .estadoPago(EstadoPago.COBRADO)
                .fechaEmision(hoy.minusDays(8))
                .fechaVencimiento(hoy.minusDays(8).plusDays(30))
                .fechaCobro(hoy.minusDays(1))
                .observaciones("Pagado con transferencia.")
                .build(),

            Comprobante.builder()
                .nroComprobante("COMP-2025-000")
                .pedidoId(1L)
                .nroPedido("gs-2025-0001")
                .odontologoId(3L)
                .odontologoNombre("Dr. Martín García")
                .trabajo("Corona Metal-Cerámica")
                .monto(new BigDecimal("15000.00"))
                .estadoPago(EstadoPago.VENCIDO)
                .fechaEmision(hoy.minusDays(45))
                .fechaVencimiento(hoy.minusDays(15))
                .observaciones("Vencido — requiere gestión de cobranza.")
                .build()
        );

        repository.saveAll(comprobantes);
        log.info("[GS-DEV] {} comprobantes de prueba cargados.", comprobantes.size());
        log.info("[GS-DEV] Saldo pendiente Dr. García: $15.000 (vencido)");
        log.info("[GS-DEV] Saldo pendiente Dra. Sánchez: $18.000 (vigente)");

        Proveedor dentalImport = proveedorRepository.save(
            Proveedor.builder()
                .nombre("Dental Import SRL")
                .cuit("30-71234567-8")
                .build()
        );
        log.info("[GS-DEV] Proveedor cargado: {}", dentalImport.getNombre());

        Proveedor luciano = proveedorRepository.save(Proveedor.builder()
                .nombre("Luciano Giménez").cuit("20-45700585-8").telefono("351-700-2020").build());
        proveedorRepository.save(Proveedor.builder()
                .nombre("Protésica del Sur").cuit("30-70999888-1").build());
        log.info("[GS-DEV] Proveedores extra cargados (Luciano Giménez, Protésica del Sur).");

        deudaProveedorRepository.save(
            DeudaProveedor.builder()
                .proveedor(luciano)
                .descripcion("Fresado tercerizado — Lote 2025-06")
                .monto(new BigDecimal("12000.00"))
                .fechaVencimiento(hoy.plusDays(20))
                .build()
        );
        log.info("[GS-DEV] Deuda con Luciano Giménez cargada: $12.000 (para el triangulado)");

        deudaProveedorRepository.save(
            DeudaProveedor.builder()
                .proveedor(dentalImport)
                .descripcion("Cerámica Vita PM9 — Lote 2025-05")
                .monto(new BigDecimal("25000.00"))
                .fechaVencimiento(hoy.plusDays(30))
                .build()
        );
        log.info("[GS-DEV] Deuda proveedor cargada: Cerámica Vita PM9 $25.000");
    }

    private void seedConfiguracionSueldos() {
        if (configSueldoRepository.count() > 0) return;

        configSueldoRepository.saveAll(List.of(
            ConfiguracionSueldo.builder()
                .empleadoId(1L).empleadoNombre("Rebeca González").rol("ADMIN")
                .telefono("351-655-1001").activo(true)
                .frecuencia(FrecuenciaPago.MENSUAL).montoBase(BigDecimal.ZERO)
                .saldoDevengado(BigDecimal.ZERO).saldoSobrante(BigDecimal.ZERO)
                .build(),
            ConfiguracionSueldo.builder()
                .empleadoId(2L).empleadoNombre("Carlos López").rol("TECNICO")
                .telefono("351-655-1002").activo(true)
                .frecuencia(FrecuenciaPago.SEMANAL).montoBase(new BigDecimal("120000"))
                .saldoDevengado(new BigDecimal("120000")).saldoSobrante(BigDecimal.ZERO)
                .ultimoPago(LocalDate.now().minusDays(7))
                .build(),
            ConfiguracionSueldo.builder()
                .empleadoId(3L).empleadoNombre("Mario Giménez").rol("TECNICO")
                .telefono("351-655-1003").activo(true)
                .frecuencia(FrecuenciaPago.DIARIO).montoBase(new BigDecimal("30000"))
                .saldoDevengado(new BigDecimal("90000")).saldoSobrante(BigDecimal.ZERO)
                .ultimoPago(LocalDate.now().minusDays(2))
                .build(),
            ConfiguracionSueldo.builder()
                .empleadoId(4L).empleadoNombre("Valentina Torres").rol("ADMINISTRATIVO")
                .telefono("351-655-1004").activo(true)
                .frecuencia(FrecuenciaPago.QUINCENAL).montoBase(new BigDecimal("180000"))
                .saldoDevengado(new BigDecimal("90000")).saldoSobrante(new BigDecimal("15000"))
                .ultimoPago(LocalDate.now().minusDays(5))
                .build(),
            ConfiguracionSueldo.builder()
                .empleadoId(5L).empleadoNombre("Diego Ferreyra").rol("TECNICO")
                .telefono("351-655-1005").activo(true)
                .frecuencia(FrecuenciaPago.SEMANAL).montoBase(new BigDecimal("110000"))
                .saldoDevengado(new BigDecimal("55000")).saldoSobrante(BigDecimal.ZERO)
                .ultimoPago(LocalDate.now().minusDays(4))
                .build()
        ));
        log.info("[GS-DEV] Configuración de sueldos cargada para 5 integrantes.");
    }
}
