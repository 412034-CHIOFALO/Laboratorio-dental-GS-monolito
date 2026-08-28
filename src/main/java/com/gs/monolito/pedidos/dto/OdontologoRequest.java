package com.gs.monolito.pedidos.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OdontologoRequest {

    @NotBlank(message = "El nombre del odontólogo es obligatorio")
    @Size(max = 150, message = "El nombre no puede superar los 150 caracteres")
    private String nombre;

    @Pattern(regexp = "^[0-9]{7,8}$|^$", message = "El DNI debe tener 7 u 8 dígitos")
    private String dni;

    @Pattern(regexp = "^[0-9]{2}-?[0-9]{8}-?[0-9]{1}$|^$", message = "El CUIT no tiene un formato válido")
    private String cuit;

    @Pattern(regexp = "^[0-9+()\\-\\s]{6,30}$|^$",
             message = "El teléfono solo puede contener números y los símbolos + - ( )")
    private String telefono;

    @Email(message = "El email no es válido")
    @Size(max = 100, message = "El email no puede superar los 100 caracteres")
    private String email;

    @Size(max = 30, message = "La matrícula no puede superar los 30 caracteres")
    private String matricula;

    @Size(max = 200, message = "El nombre de la clínica no puede superar los 200 caracteres")
    private String clinica;

    @Size(max = 250, message = "La dirección no puede superar los 250 caracteres")
    private String direccion;
}
