-- ═══════════════════════════════════════════════════════════════════════════
-- V11: (1) el pago de un sueldo guarda con qué se pagó; (2) remitentes autorizados para la bandeja de Solicitudes.
--
-- (1) Antes un sueldo cargado a mano salía SIEMPRE de la Caja Física, aunque se hubiera pagado por transferencia.
--     Ahora el pago dice EFECTIVO (sale de la Física) o TRANSFERENCIA (sale de la Bancaria), y el historial lo
--     muestra. Los pagos anteriores quedan en NULL: no se sabe con qué se pagaron.
--
-- (2) Antes TODO mail que llegaba a la casilla quedaba en "Por revisar", incluido el spam y lo que mandara cualquiera.
--     Ahora solo entran directo los mails de remitentes CONOCIDOS: el email de un odontólogo, o una persona que
--     alguien del laboratorio autorizó (por ejemplo, la secretaria de un odontólogo). El resto queda aparte, en
--     SIN_AUTORIZAR (sin adjuntos y con vencimiento), hasta que una persona lo autoriza, lo bloquea o lo descarta.
--     También se anota si el mail pudo verificarse (SPF/DKIM/DMARC): un mail "de" un odontólogo conocido que no
--     pasa la verificación no entra como confiable (podría ser alguien haciéndose pasar por él).
-- ═══════════════════════════════════════════════════════════════════════════

alter table gs_auth.pagos_sueldo
    add column medio enum('EFECTIVO','TRANSFERENCIA') null;

alter table gs_auth.solicitudes_entrantes
    modify column estado enum('CONVERTIDA','DESCARTADA','NUEVA','SIN_AUTORIZAR') not null,
    add column remitente_tipo enum('ODONTOLOGO','SECRETARIA','OTRO') null,
    add column remitente_etiqueta varchar(150) null,
    add column motivo_cuarentena varchar(255) null,
    add column remitente_verificado bit(1) null;

create table if not exists gs_auth.remitentes_autorizados (
    id bigint not null auto_increment,
    email varchar(150) not null,
    tipo enum('ODONTOLOGO','SECRETARIA','OTRO') not null,
    odontologo_id bigint null,
    etiqueta varchar(150) null,
    estado enum('AUTORIZADO','BLOQUEADO') not null,
    creado_por varchar(100) not null,
    fecha_creacion datetime(6) not null,
    primary key (id),
    unique key uk_remitente_email (email),
    key ix_remitente_odontologo (odontologo_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index ix_solicitud_remitente on gs_auth.solicitudes_entrantes (remitente_email, estado, fecha_recepcion);
