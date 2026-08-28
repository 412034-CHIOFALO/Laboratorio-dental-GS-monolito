package com.gs.monolito.auth.dto;

import com.gs.monolito.auth.model.Rol;
import com.gs.monolito.auth.model.Usuario;

public record UsuarioResponse(
    Long id,
    String username,
    String nombre,
    String apellido,
    String telefono,
    Rol rol,
    boolean enabled,
    boolean pendienteAprobacion,
    boolean terminosAceptados
) {
    public static UsuarioResponse from(Usuario u) {
        return new UsuarioResponse(
            u.getId(),
            u.getUsername(),
            u.getNombre(),
            u.getApellido(),
            u.getTelefono(),
            u.getRol(),
            u.isEnabled(),
            u.isPendienteAprobacion(),
            u.isTerminosAceptados()
        );
    }
}
