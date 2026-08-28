package com.gs.monolito.pedidos.config;

import com.gs.monolito.pedidos.model.EstadoPedido;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.pedidos.model.Prioridad;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Carga datos iniciales del módulo pedidos para desarrollo:
 *   - 4 odontólogos clientes del lab
 *   - 8 pedidos cubriendo todos los estados (RECIBIDO → EN_PROCESO → CONTROL → LISTO → ENTREGADO)
 *
 * IDs de técnicos (ver auth.config.AuthDevDataInitializer):
 *   ID 2 = tecnico1 (Carlos López)
 */
@Component
@Profile("dev")
public class PedidosDevDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PedidosDevDataInitializer.class);

    private final PedidoRepository pedidoRepository;
    private final OdontologoRepository odontologoRepository;

    public PedidosDevDataInitializer(PedidoRepository pedidoRepository,
                              OdontologoRepository odontologoRepository) {
        this.pedidoRepository = pedidoRepository;
        this.odontologoRepository = odontologoRepository;
    }

    @Override
    public void run(String... args) {
        if (pedidoRepository.count() > 0) {
            log.info("[GS-DEV] pedidos ya tiene datos — se omite la carga inicial.");
            return;
        }

        // ── 1. Odontólogos clientes ────────────────────────────────────
        Map<String, Odontologo> ods = Map.of(
            "garcia", odontologoRepository.save(Odontologo.builder()
                .nombre("Dr. Martín García")
                .dni("28456789")
                .cuit("20-28456789-3")
                .telefono("11-4567-8901")
                .email("martin.garcia@odontologia.com.ar")
                .matricula("MN 12345")
                .clinica("Clínica Odontológica Norte")
                .direccion("Av. Cabildo 2350, CABA")
                .build()),
            "sanchez", odontologoRepository.save(Odontologo.builder()
                .nombre("Dra. Laura Sánchez")
                .dni("30123456")
                .cuit("27-30123456-5")
                .telefono("11-2345-6789")
                .email("laura.sanchez@odonto.com.ar")
                .matricula("MN 23456")
                .clinica("Consultorio Dental Belgrano")
                .direccion("Mendoza 1820, CABA")
                .build()),
            "ruiz", odontologoRepository.save(Odontologo.builder()
                .nombre("Dr. Carlos Ruiz")
                .dni("25789012")
                .cuit("20-25789012-7")
                .telefono("11-5555-1234")
                .email("c.ruiz@dental.com.ar")
                .matricula("MN 34567")
                .clinica("Centro Odontológico Palermo")
                .direccion("Scalabrini Ortiz 950, CABA")
                .build()),
            "molina", odontologoRepository.save(Odontologo.builder()
                .nombre("Dra. Verónica Molina")
                .dni("32654987")
                .telefono("11-6789-0123")
                .matricula("MN 45678")
                .clinica("Odontología Integral San Telmo")
                .direccion("Defensa 750, CABA")
                .build())
        );
        log.info("[GS-DEV] {} odontólogos cargados en pedidos.", ods.size());

        // ── 2. Pedidos de prueba ───────────────────────────────────────
        LocalDate hoy = LocalDate.now();
        Odontologo garcia  = ods.get("garcia");
        Odontologo sanchez = ods.get("sanchez");

        List<Pedido> pedidos = List.of(

            // 1. RECIBIDO + URGENTE — corona recién ingresada, pendiente de asignar
            Pedido.builder()
                .nroPedido("gs-2025-0001")
                .odontologoId(garcia.getId())
                .odontologoNombre(garcia.getNombre())
                .paciente("Martín López")
                .catalogoTrabajoId(1L)
                .trabajo("Corona Metal-Cerámica")
                .fechaEntrega(hoy.plusDays(5))
                .estado(EstadoPedido.RECIBIDO)
                .prioridad(Prioridad.URGENTE)
                .precioAcordado(new BigDecimal("15000.00"))
                .observaciones("Urgente — paciente con cita el " + hoy.plusDays(5))
                .build(),

            // 2. EN_PROCESO — prótesis asignada a Carlos
            Pedido.builder()
                .nroPedido("gs-2025-0002")
                .odontologoId(garcia.getId())
                .odontologoNombre(garcia.getNombre())
                .paciente("Ana Rodríguez")
                .catalogoTrabajoId(4L)
                .trabajo("Prótesis Total Superior")
                .tecnicoId(2L)
                .tecnicoNombre("Carlos López")
                .fechaEntrega(hoy.plusDays(12))
                .estado(EstadoPedido.EN_PROCESO)
                .prioridad(Prioridad.NORMAL)
                .precioAcordado(new BigDecimal("45000.00"))
                .observaciones("Primera prótesis del paciente. Incluir ajuste de mordida.")
                .build(),

            // 3. EN_PROCESO — incrustación en proceso
            Pedido.builder()
                .nroPedido("gs-2025-0003")
                .odontologoId(sanchez.getId())
                .odontologoNombre(sanchez.getNombre())
                .paciente("Luis Fernández")
                .catalogoTrabajoId(3L)
                .trabajo("Incrustación Onlay")
                .tecnicoId(2L)
                .tecnicoNombre("Carlos López")
                .fechaEntrega(hoy.plusDays(3))
                .estado(EstadoPedido.EN_PROCESO)
                .prioridad(Prioridad.NORMAL)
                .precioAcordado(new BigDecimal("8000.00"))
                .build(),

            // 4. CONTROL — aparato funcional listo para revisión
            Pedido.builder()
                .nroPedido("gs-2025-0004")
                .odontologoId(sanchez.getId())
                .odontologoNombre(sanchez.getNombre())
                .paciente("Elena Gómez")
                .catalogoTrabajoId(5L)
                .trabajo("Aparato Funcional Bimler")
                .tecnicoId(2L)
                .tecnicoNombre("Carlos López")
                .fechaEntrega(hoy.plusDays(1))
                .estado(EstadoPedido.CONTROL)
                .prioridad(Prioridad.NORMAL)
                .precioAcordado(new BigDecimal("18000.00"))
                .observaciones("Verificar que el alambre labial quede libre del canino.")
                .build(),

            // 5. LISTO — férula entregada, queda facturación
            Pedido.builder()
                .nroPedido("gs-2025-0005")
                .odontologoId(garcia.getId())
                .odontologoNombre(garcia.getNombre())
                .paciente("Roberto Díaz")
                .catalogoTrabajoId(6L)
                .trabajo("Férula Miorelajante ATM")
                .tecnicoId(2L)
                .tecnicoNombre("Carlos López")
                .fechaEntrega(hoy.minusDays(1))
                .estado(EstadoPedido.LISTO)
                .prioridad(Prioridad.NORMAL)
                .precioAcordado(new BigDecimal("12000.00"))
                .observaciones("Pulido final aprobado. Listo para retiro.")
                .build(),

            // 6. LISTO + URGENTE — esperando retiro
            Pedido.builder()
                .nroPedido("gs-2025-0006")
                .odontologoId(sanchez.getId())
                .odontologoNombre(sanchez.getNombre())
                .paciente("Carmen Vidal")
                .catalogoTrabajoId(2L)
                .trabajo("Corona Zirconio")
                .tecnicoId(2L)
                .tecnicoNombre("Carlos López")
                .fechaEntrega(hoy)
                .estado(EstadoPedido.LISTO)
                .prioridad(Prioridad.URGENTE)
                .precioAcordado(new BigDecimal("32000.00"))
                .build(),

            // 7. ENTREGADO hace 2 días
            Pedido.builder()
                .nroPedido("gs-2025-0007")
                .odontologoId(garcia.getId())
                .odontologoNombre(garcia.getNombre())
                .paciente("Esteban Quiroga")
                .catalogoTrabajoId(1L)
                .trabajo("Corona Metal-Cerámica")
                .tecnicoId(2L)
                .tecnicoNombre("Carlos López")
                .fechaEntrega(hoy.minusDays(2))
                .estado(EstadoPedido.ENTREGADO)
                .prioridad(Prioridad.NORMAL)
                .precioAcordado(new BigDecimal("15000.00"))
                .fechaEntregaReal(hoy.minusDays(2))
                .retiradoPor("Cadetería del consultorio")
                .observacionesEntrega("Entrega sin novedades.")
                .build(),

            // 8. ENTREGADO ayer
            Pedido.builder()
                .nroPedido("gs-2025-0008")
                .odontologoId(sanchez.getId())
                .odontologoNombre(sanchez.getNombre())
                .paciente("Marta Suárez")
                .catalogoTrabajoId(3L)
                .trabajo("Carilla Porcelana")
                .tecnicoId(2L)
                .tecnicoNombre("Carlos López")
                .fechaEntrega(hoy.minusDays(1))
                .estado(EstadoPedido.ENTREGADO)
                .prioridad(Prioridad.NORMAL)
                .precioAcordado(new BigDecimal("60000.00"))
                .fechaEntregaReal(hoy.minusDays(1))
                .retiradoPor("Dra. Laura Sánchez (en persona)")
                .build()
        );

        pedidoRepository.saveAll(pedidos);
        log.info("[GS-DEV] {} pedidos de prueba cargados en pedidos.", pedidos.size());
        log.info("[GS-DEV] Estados: RECIBIDO(1) EN_PROCESO(2) CONTROL(1) LISTO(2) ENTREGADO(2)");
    }
}
