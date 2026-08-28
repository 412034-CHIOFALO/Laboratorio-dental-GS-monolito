package com.gs.monolito.catalogo.model;

/**
 * Categorías odontológicas que agrupan los tipos de trabajo dental del laboratorio.
 */
public enum Categoria {
    /** Trabajos protésicos cementados de forma permanente (coronas, puentes, incrustaciones, carillas). */
    FIJA,
    /** Prótesis que el paciente puede colocar y retirar (parciales acrílicas, totales, esqueléticas). */
    REMOVIBLE,
    /** Aparatos ortodóncicos fijos o removibles (retenedores, expansores, placas de Hawley). */
    ORTODONCIA,
    /** Dispositivos oclusales para tratamiento de la articulación temporo-mandibular. */
    ATM,
    /** Trabajos a medida que no encajan en las categorías estándar del laboratorio. */
    PERSONALIZADO
}
