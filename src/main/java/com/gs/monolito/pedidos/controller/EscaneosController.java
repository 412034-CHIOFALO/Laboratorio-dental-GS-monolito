package com.gs.monolito.pedidos.controller;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.pedidos.dto.EscaneoResponse;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.exception.ResourceNotFoundException;
import com.gs.monolito.pedidos.model.EscaneosPedido;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.pedidos.repository.EscaneosPedidoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import com.gs.monolito.pedidos.service.MallaInspector;
import com.gs.monolito.pedidos.service.PedidosDocumentoStorageService;
import com.gs.monolito.pedidos.service.TiposArchivo;
import com.gs.monolito.pedidos.service.UploadValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Controlador REST para la gestión de escaneos 3D asociados a pedidos.
 * <p>
 * Los escaneos son archivos STL, OBJ u otros formatos digitales de modelos dentales
 * (arcadas, antagonistas, implantes, etc.) que el laboratorio recibe del odontólogo
 * o genera internamente. Se almacenan en MinIO bajo el prefijo {@code escaneos/}.
 * </p>
 */
@Tag(name = "Escaneos de pedido", description = "Gestión de archivos de escaneos 3D adjuntos a un pedido (STL, OBJ, modelos digitales dentales). Los archivos se almacenan en MinIO con prefijo 'escaneos/' y se sirven en streaming a través del propio backend. Cada archivo se inspecciona por dentro al subirse.")
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
    private final MallaInspector mallaInspector;
    private final AuditoriaService auditoria;

    /** Tope de escaneos por pedido: un pedido con cientos de archivos suele ser un error de carga. */
    @Value("${gs.escaneos.max-por-pedido:40}")
    private int maxPorPedido = 40;

    @Operation(
        summary = "Listar escaneos de un pedido",
        description = "Devuelve todos los escaneos adjuntos al pedido, ordenados del más reciente al más antiguo. Cada escaneo incluye su descripción, el formato real, los triángulos, las medidas y los avisos de la inspección."
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

        Pedido pedido = pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido no encontrado: " + pedidoId));
        if (file.isEmpty()) {
            throw new BusinessException("El archivo está vacío");
        }
        uploadValidator.validarEscaneo(file);
        String nombre = nombreSeguro(file.getOriginalFilename());
        if (escaneoRepo.countByPedidoId(pedidoId) >= maxPorPedido) {
            throw new BusinessException("El pedido ya tiene " + maxPorPedido + " escaneos, que es el máximo. Borrá alguno antes de subir otro.");
        }

        // Se revisa por dentro ANTES de guardar: un STL cortado, o un archivo que no es lo que dice, se rechaza acá con
        // un mensaje que explica qué está mal, y no cuando el técnico intenta abrirlo.
        MallaInspector.InformeMalla informe;
        try (InputStream in = file.getInputStream()) {
            informe = mallaInspector.inspeccionar(in, file.getSize(), extensionDe(nombre));
        } catch (IOException e) {
            throw new BusinessException("Error al leer el archivo");
        }
        Optional<EscaneosPedido> repetido = escaneoRepo.findFirstByPedidoIdAndSha256(pedidoId, informe.sha256());
        if (repetido.isPresent()) {
            throw new BusinessException("Ese mismo archivo ya está cargado en este pedido (\"" + repetido.get().getFileName() + "\").");
        }

        String objectKey;
        try (InputStream in = file.getInputStream()) {
            objectKey = minioStorageService.subirStream(in, file.getSize(),
                    MediaType.APPLICATION_OCTET_STREAM_VALUE, nombre, pedidoId, "escaneos");
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
                .fileName(nombre)
                .contentType(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .tamanioBytes(file.getSize())
                .descripcion(descripcion)
                .subidoPor(subidoPor)
                .formato(informe.formato())
                .triangulos(informe.triangulos())
                .vertices(informe.vertices())
                .dimXMm(informe.dimX())
                .dimYMm(informe.dimY())
                .dimZMm(informe.dimZ())
                .sha256(informe.sha256())
                .advertencias(informe.advertencias().isEmpty() ? null : recortar(String.join(" | ", informe.advertencias()), 600))
                .build();

        EscaneosPedido saved;
        try {
            saved = escaneoRepo.save(escaneo);
        } catch (RuntimeException e) {
            // La base falló después de subir el archivo: se borra para no dejar un objeto huérfano en MinIO.
            minioStorageService.eliminar(objectKey);
            throw e;
        }
        log.info("[ESCANEO] '{}' subido al pedido {} ({})", saved.getFileName(), pedidoId, resumen(informe));
        auditoria.registrar(subidoPor, "ESCANEO", "Escaneo subido", "Pedido " + pedido.getNroPedido(),
                nombre + " · " + resumen(informe));
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
        // STL/OBJ/PLY no se muestran en el navegador: siempre binario y como
        // descarga, sin importar el contentType que haya quedado guardado.
        ResponseEntity.BodyBuilder respuesta = ResponseEntity.ok();
        if (e.getTamanioBytes() != null) respuesta.contentLength(e.getTamanioBytes());
        if (e.getSha256() != null) respuesta.eTag("\"" + e.getSha256() + "\"");
        return respuesta
                .contentType(TiposArchivo.mediaTypePara(e.getFileName()))
                .header(HttpHeaders.CONTENT_DISPOSITION, TiposArchivo.contentDisposition(e.getFileName()))
                .body(new InputStreamResource(in));
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
            @PathVariable @Positive Long escaneoId,
            Authentication auth) {
        EscaneosPedido e = escaneoRepo.findById(escaneoId)
                .filter(x -> x.getPedidoId().equals(pedidoId))
                .orElseThrow(() -> new ResourceNotFoundException("Escaneo no encontrado"));
        minioStorageService.eliminar(e.getObjectKey());
        escaneoRepo.delete(e);
        log.info("[ESCANEO] {} eliminado del pedido {}", escaneoId, pedidoId);
        String quien = auth != null ? auth.getName() : "desconocido";
        auditoria.registrar(quien, "ESCANEO", "Escaneo eliminado",
                "Pedido " + pedidoRepository.findById(pedidoId).map(Pedido::getNroPedido).orElse("#" + pedidoId),
                e.getFileName() + (e.getTriangulos() != null ? " · " + e.getTriangulos() + " triángulos" : ""));
        return ResponseEntity.noContent().build();
    }

    /**
     * El nombre tal como lo mandó el navegador puede traer una ruta ("C:\\scans\\a.stl", "../x.stl") o caracteres de
     * control: se queda solo con el último tramo, sin controles y con un largo razonable.
     */
    static String nombreSeguro(String original) {
        String n = original == null ? "" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (n.length() > 200) {
            int punto = n.lastIndexOf('.');
            String ext = punto >= 0 && n.length() - punto <= 10 ? n.substring(punto) : "";
            n = n.substring(0, 200 - ext.length()) + ext;
        }
        return n.isEmpty() ? "escaneo" : n;
    }

    static String extensionDe(String nombre) {
        int punto = nombre.lastIndexOf('.');
        return punto < 0 ? "" : nombre.substring(punto + 1).toLowerCase(Locale.ROOT);
    }

    private static String resumen(MallaInspector.InformeMalla i) {
        return i.formato() + " · " + i.triangulos() + " triángulos · " + medida(i.dimX()) + " × " + medida(i.dimY()) + " × " + medida(i.dimZ()) + " mm";
    }

    private static String medida(BigDecimal v) {
        return v.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private EscaneoResponse toResponse(EscaneosPedido e) {
        return new EscaneoResponse(e.getId(), e.getPedidoId(), e.getFileName(),
                e.getContentType(), e.getTamanioBytes(), e.getDescripcion(),
                e.getSubidoPor(), e.getFechaSubida(),
                e.getFormato(), e.getTriangulos(), e.getVertices(), e.getDimXMm(), e.getDimYMm(), e.getDimZMm(),
                e.getAdvertencias() == null || e.getAdvertencias().isBlank() ? List.of() : List.of(e.getAdvertencias().split(" \\| ")));
    }
}
