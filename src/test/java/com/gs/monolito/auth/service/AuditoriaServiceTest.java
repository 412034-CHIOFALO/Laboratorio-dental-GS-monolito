package com.gs.monolito.auth.service;

import com.gs.monolito.auth.model.AuditoriaEvento;
import com.gs.monolito.auth.repository.AuditoriaEventoRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * La auditoría comparte transacción con lo que anota: si un texto no entra en su columna, el guardado falla y se
 * revierte el pago o el pedido. Por eso los textos largos se cortan en vez de romper.
 */
class AuditoriaServiceTest {

    private final AuditoriaEventoRepository repo = mock(AuditoriaEventoRepository.class);
    private final AuditoriaService service = new AuditoriaService(repo);

    private AuditoriaEvento guardado() {
        ArgumentCaptor<AuditoriaEvento> c = ArgumentCaptor.forClass(AuditoriaEvento.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    @Test
    void unEventoNormalSeGuardaTalCual() {
        service.registrar("mariana", "EDITAR", "Edición de odontólogo", "Odontólogo Juan Pérez (id 7)", "teléfono: …4521 → …9876");

        AuditoriaEvento e = guardado();
        assertThat(e.getUsuario()).isEqualTo("mariana");
        assertThat(e.getDetalle()).isEqualTo("teléfono: …4521 → …9876");
    }

    @Test
    void losTextosLargosSeCortanAlLargoDeSuColumna() {
        service.registrar("u".repeat(300), "T".repeat(80), "a".repeat(400), "e".repeat(400), "d".repeat(900));

        AuditoriaEvento e = guardado();
        assertThat(e.getUsuario()).hasSize(100);
        assertThat(e.getTipo()).hasSize(50);
        assertThat(e.getAccion()).hasSize(200);
        assertThat(e.getEntidad()).hasSize(200);
        assertThat(e.getDetalle()).hasSize(500).endsWith("…");
    }

    @Test
    void unDetalleNuloNoRompe() {
        service.registrar("sistema", "BACKUP", "Backup", "Base", null);

        assertThat(guardado().getDetalle()).isEmpty();
    }
}
