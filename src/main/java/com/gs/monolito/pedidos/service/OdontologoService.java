package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.dto.OdontologoRequest;
import com.gs.monolito.pedidos.dto.OdontologoResponse;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.exception.ConflictException;
import com.gs.monolito.pedidos.exception.ResourceNotFoundException;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Implementación de {@link IOdontologoService}. Implementa el patrón
 * find-or-create en {@link #buscarOCrearPorNombre}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OdontologoService implements IOdontologoService {

    private static final Logger log = LoggerFactory.getLogger(OdontologoService.class);

    private static final Pattern PATRON_DNI = Pattern.compile("^[0-9]{7,8}$");
    private static final Pattern PATRON_CUIT_RAW = Pattern.compile("^[0-9]{2}-?[0-9]{8}-?[0-9]{1}$");
    private static final Pattern PATRON_MATRICULA = Pattern.compile("^(MN|MP|MAT)[\\s-]*[0-9]+$", Pattern.CASE_INSENSITIVE);

    private final OdontologoRepository repository;
    private final PedidoRepository pedidoRepository;

    @Value("${gs.odontologos.meses-inactividad:6}")
    private int mesesInactividad;

    @Override
    public List<OdontologoResponse> listarActivos() {
        return construirRespuestas(repository.findByActivoTrueOrderByNombreAsc());
    }

    @Override
    public List<OdontologoResponse> listarTodos() {
        return construirRespuestas(repository.findAllByOrderByNombreAsc());
    }

    private List<OdontologoResponse> construirRespuestas(List<Odontologo> odontologos) {
        Map<Long, LocalDateTime> ultimaActividad = mapaUltimaActividad();
        LocalDateTime corte = LocalDateTime.now().minusMonths(mesesInactividad);

        return odontologos.stream()
                .map(o -> {
                    LocalDateTime ultimo = ultimaActividad.get(o.getId());
                    boolean inactivo = (ultimo == null) || ultimo.isBefore(corte);
                    return OdontologoResponse.from(o, ultimo, inactivo);
                })
                .toList();
    }

    private Map<Long, LocalDateTime> mapaUltimaActividad() {
        Map<Long, LocalDateTime> mapa = new HashMap<>();
        for (Object[] row : pedidoRepository.ultimaActividadPorOdontologo()) {
            if (row[0] != null && row[1] != null) {
                mapa.put((Long) row[0], (LocalDateTime) row[1]);
            }
        }
        return mapa;
    }

    @Override
    public List<OdontologoResponse> buscarPorNombre(String fragmento) {
        if (fragmento == null || fragmento.isBlank()) {
            return listarActivos();
        }
        String q = fragmento.trim();

        if (PATRON_DNI.matcher(q).matches()) {
            return repository.findByActivoTrueAndDni(q)
                    .map(o -> List.of(OdontologoResponse.from(o)))
                    .orElseGet(List::of);
        }
        if (PATRON_CUIT_RAW.matcher(q).matches()) {
            String cuitNormalizado = normalizarCuit(q);
            return repository.findByActivoTrueAndCuit(cuitNormalizado)
                    .map(o -> List.of(OdontologoResponse.from(o)))
                    .orElseGet(List::of);
        }
        if (PATRON_MATRICULA.matcher(q).matches()) {
            return repository.findByActivoTrueAndMatriculaIgnoreCase(q)
                    .map(o -> List.of(OdontologoResponse.from(o)))
                    .orElseGet(List::of);
        }

        Set<Odontologo> resultados = new LinkedHashSet<>(
                repository.findByActivoTrueAndNombreContainingIgnoreCaseOrderByNombreAsc(q));
        return resultados.stream().map(OdontologoResponse::from).toList();
    }

    @Override
    public OdontologoResponse buscarPorId(Long id) {
        return repository.findById(id)
                .map(OdontologoResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Odontologo", id));
    }

    @Override
    @Transactional
    public OdontologoResponse crear(OdontologoRequest request) {
        String nombre = normalizarNombre(request.getNombre());
        String dni    = blankToNull(request.getDni());
        String cuit   = normalizarCuit(blankToNull(request.getCuit()));

        repository.findByActivoTrueAndNombreIgnoreCase(nombre).ifPresent(existente -> {
            throw new ConflictException("Ya existe un odontólogo con nombre: " + existente.getNombre());
        });
        if (dni != null && repository.existsByDni(dni)) {
            throw new ConflictException("Ya existe un odontólogo con DNI: " + dni);
        }
        if (cuit != null && repository.existsByCuit(cuit)) {
            throw new ConflictException("Ya existe un odontólogo con CUIT: " + cuit);
        }

        Odontologo nuevo = Odontologo.builder()
                .nombre(nombre)
                .dni(dni)
                .cuit(cuit)
                .telefono(blankToNull(request.getTelefono()))
                .email(blankToNull(request.getEmail()))
                .matricula(blankToNull(request.getMatricula()))
                .clinica(blankToNull(request.getClinica()))
                .direccion(blankToNull(request.getDireccion()))
                .build();
        return OdontologoResponse.from(repository.save(nuevo));
    }

    @Override
    @Transactional
    public OdontologoResponse actualizar(Long id, OdontologoRequest request) {
        Odontologo o = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Odontologo", id));

        String dni  = blankToNull(request.getDni());
        String cuit = normalizarCuit(blankToNull(request.getCuit()));

        if (dni != null && !dni.equals(o.getDni()) && repository.existsByDni(dni)) {
            throw new ConflictException("Ya existe otro odontólogo con DNI: " + dni);
        }
        if (cuit != null && !cuit.equals(o.getCuit()) && repository.existsByCuit(cuit)) {
            throw new ConflictException("Ya existe otro odontólogo con CUIT: " + cuit);
        }

        o.setNombre(normalizarNombre(request.getNombre()));
        o.setDni(dni);
        o.setCuit(cuit);
        o.setTelefono(blankToNull(request.getTelefono()));
        o.setEmail(blankToNull(request.getEmail()));
        o.setMatricula(blankToNull(request.getMatricula()));
        o.setClinica(blankToNull(request.getClinica()));
        o.setDireccion(blankToNull(request.getDireccion()));
        return OdontologoResponse.from(repository.save(o));
    }

    @Override
    @Transactional
    public void desactivar(Long id) {
        Odontologo o = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Odontologo", id));
        o.setActivo(false);
        repository.save(o);
        log.info("[GS-PEDIDOS] Odontólogo desactivado: {} (id={})", o.getNombre(), id);
    }

    @Override
    @Transactional
    public Odontologo buscarOCrearPorNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new BusinessException("El nombre del odontólogo es obligatorio.");
        }
        String normalizado = normalizarNombre(nombre);
        return repository.findByActivoTrueAndNombreIgnoreCase(normalizado)
                .orElseGet(() -> {
                    log.info("[GS-PEDIDOS] Odontólogo nuevo creado on-the-fly: {}", normalizado);
                    Odontologo nuevo = Odontologo.builder().nombre(normalizado).build();
                    return repository.save(nuevo);
                });
    }

    private String normalizarNombre(String s) {
        return s == null ? null : s.trim().replaceAll("\\s+", " ");
    }

    private String normalizarCuit(String cuit) {
        if (cuit == null || cuit.isBlank()) return null;
        String digitos = cuit.replaceAll("[^0-9]", "");
        if (digitos.length() != 11) return cuit;
        return digitos.substring(0, 2) + "-" + digitos.substring(2, 10) + "-" + digitos.substring(10);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
