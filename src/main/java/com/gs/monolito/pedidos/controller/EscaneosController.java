package com.gs.monolito.pedidos.controller;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

import com.gs.monolito.pedidos.dto.EscaneoResponse;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.exception.ResourceNotFoundException;
import com.gs.monolito.pedidos.model.EscaneosPedido;
import com.gs.monolito.pedidos.repository.EscaneosPedidoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import com.gs.monolito.pedidos.service.PedidosDocumentoStorageService;
import com.gs.monolito.pedidos.service.UploadValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Controlador REST para la gestión de escaneos 3D asociados a pedidos.
 * <p>
 * Los escaneos son archivos STL, OBJ u otros formatos digitales de modelos dentales
 * (arcadas, antagonistas, implantes, etc.) que el laboratorio recibe del odontólogo
 * o genera internamente. Se almacenan en MinIO bajo el prefijo {@code escaneos/}.
 * </p>
 */
@Tag(name = "Escaneos de pedido", description = "Gestión de archivos de escaneos 3D adjuntos a un pedido (STL, OBJ, modelos digitales dentales). Los archivos se almacenan en MinIO con prefijo 'escaneos/' y se acceden mediante URLs prefirmadas de 30 minutos.")
@RestController
@RequestMapping("/api/pedidos/{pedidoId}/escaneos")
@RequiredArgsConstructor
@Slf4j
@Validated
public class EscaneosController {

    private final EscaneosPedidoRepository escaneoRepo;
    private final PedidoRepository pedidoRepository;
    private final PedidosDocumentoStorageService minioStorageService;
    private final UploadValidator uploadValidator;

    @Operation(
        summary = "Listar escaneos de un pedido",
        description = "Devuelve todos los escaneos adjuntos al pedido, ordenados del más reciente al más antiguo. Cada escaneo incluye su descripción y una URL preformada temporal."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de escaneos obtenida correctamente"),
        @ApiResponse(responseCode = "404", description = "Pedido no encontrado")
    })
    @GetMapping
    public ResponseEntity<List<EscaneoResponse>> listar(
            @Parameter(description = "ID del pedido", example = "42")
            @PathVariable @Positive Long pedidoId) {
        List<EscaneoResponse> resp = escaneoRepo
                .findByPedidoIdOrderByFechaSubidaDesc(pedidoId)
                .stream().map(this::toResponse).collect(Collectors.toList());
        return ResponseEntity.ok(resp);
    }

    @Operation(
        summary = "Subir escaneo a un pedido",
        description = "Sube un archivo de escaneo (STL, OBJ, etc.) al pedido especificado. Se puede agregar una descripción libre, por ejemplo 'Arcada superior', 'Modelo antagonista'. El archivo se almacena en MinIO bajo el prefijo 'escaneos/'."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Escaneo subido correctamente"),
        @ApiResponse(responseCode = "400", description = "Archivo vacío o error de lectura"),
        @ApiResponse(responseCode = "404", description = "Pedido no encontrado"),
        @ApiResponse(responseCode = "503", description = "MinIO no disponible — el archivo no pudo ser guardado")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EscaneoResponse> subir(
            @Parameter(description = "ID del pedido al que se adjunta el escaneo", example = "42")
            @PathVariable @Positive Long pedidoId,
            @Parameter(description = "Archivo de escaneo a subir (STL, OBJ, etc.)")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Descripción del escaneo. Ej: 'Arcada superior', 'Modelo antagonista'", example = "Arcada superior")
            @RequestParam(value = "descripcion", required = false) String descripcion,
            Authentication auth) {

        if (!pedidoRepository.existsById(pedidoId)) {
            throw new ResourceNotFoundException("Pedido no encontrado: " + pedidoId);
        }
        if (file.isEmpty()) {
            throw new BusinessException("El archivo está vacío");
        }
        uploadValidator.validarEscaneo(file);

        String objectKey;
        try (InputStream in = file.getInputStream()) {
            objectKey = minioStorageService.subirStream(in, file.getSize(), file.getContentType(),
                    file.getOriginalFilename(), pedidoId, "escaneos");
        } catch (IOException e) {
            throw new BusinessException("Error al leer el archivo");
        }
        if (objectKey == null) {
            throw new BusinessException("No se pudo guardar el archivo (MinIO no disponible)");
        }

        String subidoPor = auth != null ? auth.getName() : "desconocido";
        EscaneosPedido escaneo = EscaneosPedido.builder()
                .pedidoId(pedidoId)
                .objectKey(objectKey)
                .fileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "archivo")
                .contentType(file.getContentType())
                .tamanioBytes(file.getSize())
                .descripcion(descripcion)
                .subidoPor(subidoPor)
                .build();

        EscaneosPedido saved = escaneoRepo.save(escaneo);
        log.info("[ESCANEO] '{}' subido al pedido {}", saved.getFileName(), pedidoId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @Operation(
        summary = "Descargar/ver el archivo de un escaneo",
        description = "Sirve el archivo almacenado en MinIO en streaming a través del propio backend (proxy), sin exponer MinIO directamente al navegador."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Archivo servido correctamente"),
        @ApiResponse(responseCode = "404", description = "Escaneo no encontrado o no pertenece al pedido"),
        @ApiResponse(responseCode = "503", description = "MinIO no disponible — no se pudo obtener el archivo")
    })
    @GetMapping("/{escaneoId}/archivo")
    public ResponseEntity<InputStreamResource> archivo(
            @Parameter(description = "ID del pedido", example = "42")
            @PathVariable @Positive Long pedidoId,
            @Parameter(description = "ID del escaneo", example = "3")
            @PathVariable @Positive Long escaneoId) {
        EscaneosPedido e = escaneoRepo.findById(escaneoId)
                .filter(x -> x.getPedidoId().equals(pedidoId))
                .orElseThrow(() -> new ResourceNotFoundException("Escaneo no encontrado"));
        InputStream in = minioStorageService.descargar(e.getObjectKey());
        if (in == null) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok()
                .contentType(mediaTypeSeguro(e.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + e.getFileName() + "\"")
                .body(new InputStreamResource(in));
    }

    /** Parsea el content-type guardado; ante uno no estándar o vacío, cae a octet-stream. */
    private MediaType mediaTypeSeguro(String contentType) {
        if (contentType == null || contentType.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @Operation(
        summary = "Eliminar escaneo de un pedido",
        description = "Elimina el escaneo tanto de MinIO (objeto S3) como de la base de datos. La operación es irreversible."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Escaneo eliminado correctamente"),
        @ApiResponse(responseCode = "404", description = "Escaneo no encontrado o no pertenece al pedido")
    })
    @DeleteMapping("/{escaneoId}")
    public ResponseEntity<Void> eliminar(
            @Parameter(description = "ID del pedido", example = "42")
            @PathVariable @Positive Long pedidoId,
            @Parameter(description = "ID del escaneo a eliminar", example = "3")
            @PathVariable @Positive Long escaneoId) {
        EscaneosPedido e = escaneoRepo.findById(escaneoId)
                .filter(x -> x.getPedidoId().equals(pedidoId))
                .orElseThrow(() -> new ResourceNotFoundException("Escaneo no encontrado"));
        minioStorageService.eliminar(e.getObjectKey());
        escaneoRepo.delete(e);
        log.info("[ESCANEO] {} eliminado del pedido {}", escaneoId, pedidoId);
        return ResponseEntity.noContent().build();
    }

    private EscaneoResponse toResponse(EscaneosPedido e) {
        return new EscaneoResponse(e.getId(), e.getPedidoId(), e.getFileName(),
                e.getContentType(), e.getTamanioBytes(), e.getDescripcion(),
                e.getSubidoPor(), e.getFechaSubida());
    }
}
