package com.gs.monolito.auth.dto;

import com.gs.monolito.auth.model.Rol;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

    @NotBlank(message = "El nombre no puede estar vacío")
    @Size(max = 60)
    String nombre,

    @NotBlank(message = "El apellido no puede estar vacío")
    @Size(max = 60)
    String apellido,

    @NotBlank(message = "El username no puede estar vacío")
    @Size(min = 3, max = 50, message = "El username debe tener entre 3 y 50 caracteres")
    String username,

    @NotBlank(message = "La contraseña no puede estar vacía")
    @Size(min = 8, max = 100, message = "La contraseña debe tener entre 8 y 100 caracteres")
    String password,

    @NotNull(message = "El rol es obligatorio")
    Rol rol

) {}
