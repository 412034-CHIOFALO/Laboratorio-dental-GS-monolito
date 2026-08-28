package com.gs.monolito.finanzas.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProveedorRequest {

    @NotBlank(message = "El nombre del proveedor es obligatorio")
    @Size(max = 200)
    private String nombre;

    @Pattern(regexp = "^[0-9]{2}-?[0-9]{8}-?[0-9]{1}$|^$",
             message = "El CUIT debe tener 11 dígitos (ej: 30-12345678-9)")
    private String cuit;

    @Email(message = "El email no es válido")
    @Size(max = 100)
    private String email;

    @Pattern(regexp = "^[0-9+()\\-\\s]{6,20}$|^$",
             message = "El teléfono solo puede contener números y los símbolos + - ( )")
    private String telefono;

    @Size(max = 300)
    private String direccion;
}
