package com.gs.monolito.stock.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.stock.dto.MaterialRequest;
import com.gs.monolito.stock.model.CategoriaMaterial;
import com.gs.monolito.stock.model.Material;
import com.gs.monolito.stock.model.MovimientoStock;
import com.gs.monolito.stock.model.TipoMovimiento;
import com.gs.monolito.stock.repository.MaterialRepository;
import com.gs.monolito.stock.repository.MovimientoStockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Todo cambio de stock deja su movimiento, también el que se hace editando el material. */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock private MaterialRepository materialRepo;
    @Mock private MovimientoStockRepository movimientoRepo;
    @Mock private AuditoriaService auditoria;

    @InjectMocks private StockService service;

    private Material yeso(double stock) {
        return Material.builder().id(1L).nombre("Yeso tipo IV").categoria(CategoriaMaterial.YESO)
                .stockActual(stock).stockMinimo(2.0).unidadMedida("kg").activo(true).descuentaStock(true).build();
    }

    private MaterialRequest request(double stock) {
        MaterialRequest r = new MaterialRequest();
        r.setNombre("Yeso tipo IV");
        r.setCategoria(CategoriaMaterial.YESO);
        r.setStockActual(stock);
        r.setStockMinimo(2.0);
        r.setUnidadMedida("kg");
        return r;
    }

    @Test
    void editarElStockDeUnMaterial_dejaUnMovimientoDeAjuste() {
        when(materialRepo.findById(1L)).thenReturn(Optional.of(yeso(10)));
        when(materialRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.actualizar(1L, request(7));

        ArgumentCaptor<MovimientoStock> mov = ArgumentCaptor.forClass(MovimientoStock.class);
        verify(movimientoRepo).save(mov.capture());
        assertThat(mov.getValue().getTipo()).isEqualTo(TipoMovimiento.AJUSTE);
        assertThat(mov.getValue().getStockResultante()).isEqualTo(7.0);
        assertThat(mov.getValue().getMotivo()).contains("10.0");
        verify(auditoria).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void editarOtrosDatosSinTocarElStock_noInventaMovimientos() {
        when(materialRepo.findById(1L)).thenReturn(Optional.of(yeso(10)));
        when(materialRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.actualizar(1L, request(10));

        verify(movimientoRepo, never()).save(any());
    }

    @Test
    void existeMaterial_noLanza_niConIdNulo() {
        when(materialRepo.findByNombreIgnoreCase("Inexistente")).thenReturn(Optional.empty());

        assertThat(service.existeMaterial("Inexistente", null)).isFalse();
    }
}
