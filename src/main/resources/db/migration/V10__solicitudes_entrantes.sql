-- ═══════════════════════════════════════════════════════════════════════════
-- V10: solicitudes entrantes (mails de los odontólogos).
--
-- Un mail ya no crea un pedido solo: queda como SOLICITUD, con lo que se pudo extraer del texto (paciente, trabajo,
-- fecha, urgencia, precio) y sus adjuntos, y una persona la convierte en pedido o la descarta. El Message-ID del mail
-- es único: el mismo mail nunca se carga dos veces aunque falle el marcado como leído y se vuelva a leer.
-- ═══════════════════════════════════════════════════════════════════════════

create table if not exists gs_auth.`solicitudes_entrantes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `version` bigint NOT NULL DEFAULT '0',
  `canal` enum('EMAIL','WEB','WHATSAPP') NOT NULL,
  `message_id` varchar(255) NOT NULL,
  `remitente_email` varchar(150) DEFAULT NULL,
  `remitente_nombre` varchar(150) DEFAULT NULL,
  `asunto` varchar(255) DEFAULT NULL,
  `cuerpo` text,
  `fecha_mensaje` datetime(6) DEFAULT NULL,
  `fecha_recepcion` datetime(6) NOT NULL,
  `estado` enum('CONVERTIDA','DESCARTADA','NUEVA') NOT NULL,
  `odontologo_id` bigint DEFAULT NULL,
  `odontologo_nombre` varchar(150) DEFAULT NULL,
  `sug_paciente` varchar(150) DEFAULT NULL,
  `sug_trabajo` varchar(200) DEFAULT NULL,
  `sug_catalogo_trabajo_id` bigint DEFAULT NULL,
  `sug_fecha_entrega` date DEFAULT NULL,
  `sug_prioridad` enum('NORMAL','URGENTE') DEFAULT NULL,
  `sug_precio` decimal(12,2) DEFAULT NULL,
  `sug_observaciones` text,
  `nota_adjuntos` varchar(600) DEFAULT NULL,
  `pedido_id` bigint DEFAULT NULL,
  `motivo_descarte` varchar(255) DEFAULT NULL,
  `resuelto_por` varchar(100) DEFAULT NULL,
  `fecha_resolucion` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_solicitud_message_id` (`message_id`),
  KEY `ix_solicitud_estado` (`estado`,`fecha_recepcion`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`solicitud_adjuntos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `solicitud_id` bigint NOT NULL,
  `object_key` varchar(500) NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `content_type` varchar(100) DEFAULT NULL,
  `tamanio_bytes` bigint DEFAULT NULL,
  `tipo` enum('DOCUMENTO','ESCANEO') NOT NULL,
  `sha256` char(64) DEFAULT NULL,
  `formato` varchar(20) DEFAULT NULL,
  `triangulos` bigint DEFAULT NULL,
  `vertices` bigint DEFAULT NULL,
  `dim_x_mm` decimal(14,3) DEFAULT NULL,
  `dim_y_mm` decimal(14,3) DEFAULT NULL,
  `dim_z_mm` decimal(14,3) DEFAULT NULL,
  `advertencias` varchar(600) DEFAULT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_solicitud_adjuntos_solicitud` (`solicitud_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Para reconocer al odontólogo por el email desde el que escribe.
create index ix_odontologo_email on gs_auth.odontologos (email);
