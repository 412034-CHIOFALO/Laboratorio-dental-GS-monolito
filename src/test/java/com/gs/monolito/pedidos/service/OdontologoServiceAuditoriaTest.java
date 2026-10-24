package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.pedidos.dto.OdontologoRequest;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alta, edición y baja de odontólogos dejan rastro en Auditoría. El teléfono (del que depende el aviso de "pedido
 * listo") y el email se anotan enmascarados; una edición que no cambia nada no genera ruido.
 */
@ExtendWith(MockitoExtension.class)
class OdontologoServiceAuditoriaTest {

    @Mock private OdontologoRepository repository;
    @Mock private PedidoRepository pedidoRepository;
    @Mock private AuditoriaService auditoria;

    private OdontologoService service;

    @BeforeEach
    void preparar() {
        service = new OdontologoService(repository, pedidoRepository, auditoria);
        lenient().when(repository.save(any(Odontologo.class))).thenAnswer(inv -> {
            Odontologo o = inv.getArgument(0);
            if (o.getId() == null) o.setId(7L);
            return o;
        });
    }

    private Odontologo existente() {
        return Odontologo.builder().id(7L).nombre("Juan Pérez").clinica("Norte").telefono("1155554521")
                .email("juan.perez@clinica.com").build();
    }

    private OdontologoRequest pedido(String nombre, String clinica, String telefono, String email) {
        OdontologoRequest r = new OdontologoRequest();
        r.setNombre(nombre);
        r.setClinica(clinica);
        r.setTelefono(telefono);
        r.setEmail(email);
        return r;
    }

    @Test
    void elAltaQuedaAuditada_conLosDatosPersonalesEnmascarados() {
        when(repository.findByActivoTrueAndNombreIgnoreCase(anyString())).thenReturn(Optional.empty());

        service.crear(pedido("Juan Pérez", "Norte", "1155554521", "juan.perez@clinica.com"));

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), org.mockito.ArgumentMatchers.eq("CREAR"), org.mockito.ArgumentMatchers.eq("Alta de odontólogo"),
                org.mockito.ArgumentMatchers.eq("Odontólogo Juan Pérez (id 7)"), detalle.capture());
        assertThat(detalle.getValue()).contains("clínica: Norte").contains("teléfono: …4521").contains("email: j***@clinica.com")
                .doesNotContain("1155554521").doesNotContain("juan.perez");
    }

    @Test
    void cambiarElTelefonoQuedaRegistrado_conAntesYDespuesEnmascarados() {
        when(repository.findById(7L)).thenReturn(Optional.of(existente()));

        service.actualizar(7L, pedido("Juan Pérez", "Norte", "1144449876", "juan.perez@clinica.com"));

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), org.mockito.ArgumentMatchers.eq("EDITAR"), org.mockito.ArgumentMatchers.eq("Edición de odontólogo"),
                org.mockito.ArgumentMatchers.eq("Odontólogo Juan Pérez (id 7)"), detalle.capture());
        assertThat(detalle.getValue()).isEqualTo("teléfono: …4521 → …9876");
    }

    @Test
    void laEdicionQueNoCambiaNadaNoGeneraEvento() {
        when(repository.findById(7L)).thenReturn(Optional.of(existente()));

        service.actualizar(7L, pedido("Juan Pérez", "Norte", "1155554521", "juan.perez@clinica.com"));

        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void renombrarAlOdontologoDejaLaEntidadConElNombreViejo_yElCambioEnElDetalle() {
        when(repository.findById(7L)).thenReturn(Optional.of(existente()));

        service.actualizar(7L, pedido("Juan Carlos Pérez", "Norte", "1155554521", "juan.perez@clinica.com"));

        ArgumentCaptor<String> entidad = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), any(), any(), entidad.capture(), detalle.capture());
        assertThat(entidad.getValue()).isEqualTo("Odontólogo Juan Pérez (id 7)");
        assertThat(detalle.getValue()).isEqualTo("nombre: Juan Pérez → Juan Carlos Pérez");
    }

    @Test
    void laBajaQuedaAuditada() {
        when(repository.findById(7L)).thenReturn(Optional.of(existente()));

        service.desactivar(7L);

        verify(auditoria).registrar(any(), org.mockito.ArgumentMatchers.eq("ELIMINAR"), org.mockito.ArgumentMatchers.eq("Baja de odontólogo"),
                org.mockito.ArgumentMatchers.eq("Odontólogo Juan Pérez (id 7)"), anyString());
    }

    @Test
    void crearUnOdontologoAlCargarUnPedidoConUnNombreNuevo_quedaMarcadoParaRevisarDuplicados() {
        when(repository.findByActivoTrueAndNombreIgnoreCase("Perez Juan")).thenReturn(Optional.empty());

        service.buscarOCrearPorNombre("  Perez   Juan ");

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), org.mockito.ArgumentMatchers.eq("CREAR"),
                org.mockito.ArgumentMatchers.eq("Alta automática de odontólogo"),
                org.mockito.ArgumentMatchers.eq("Odontólogo Perez Juan (id 7)"), detalle.capture());
        assertThat(detalle.getValue()).contains("duplicado");
    }

    @Test
    void siElOdontologoYaExisteNoSeRegistraNada() {
        when(repository.findByActivoTrueAndNombreIgnoreCase("Juan Pérez")).thenReturn(Optional.of(existente()));

        service.buscarOCrearPorNombre("Juan Pérez");

        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }
}
