-- ═══════════════════════════════════════════════════════════════════════════
-- V4: línea base del esquema REAL (gs_auth).
--
-- Por qué existe: V1-V3 crean y alteran tablas en gs_pedidos, gs_stock, gs_finanzas
-- y gs_catalogo, pero la app nunca las usó. Con MySQL, Hibernate ignora el
-- calificador @Table(schema=...) y trabaja contra el schema de la conexión
-- (gs_auth). Las tablas reales las creaba Hibernate (ddl-auto=update), sin dueño
-- versionado: por eso la columna `version` de V3 no llegó a las tablas reales y las
-- filas viejas quedaron con version NULL (500 al actualizar stock).
--
-- A partir de acá Flyway es el dueño de gs_auth: este archivo describe las 20 tablas
-- tal como están en producción (exportadas con mysqldump --no-data) y
-- spring.jpa.hibernate.ddl-auto pasa a "validate": si una entidad y el esquema no
-- coinciden, la app no arranca (en CI, en vez de fallar en producción). Todo cambio
-- de esquema nuevo va en una migración V5, V6...
--
-- "if not exists": en la base de producción las tablas ya existen y esto no hace
-- nada; en una base nueva las crea. Las tablas de los schemas viejos que dejan
-- V1-V3 quedan vacías y sin uso.
-- ═══════════════════════════════════════════════════════════════════════════


create table if not exists gs_auth.`auditoria_eventos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `timestamp` datetime(6) NOT NULL,
  `tipo` varchar(50) NOT NULL,
  `usuario` varchar(100) NOT NULL,
  `accion` varchar(200) NOT NULL,
  `entidad` varchar(200) NOT NULL,
  `detalle` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`caja_movimientos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `concepto` varchar(300) NOT NULL,
  `creado_por` varchar(50) DEFAULT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `fecha_movimiento` date NOT NULL,
  `monto` decimal(12,2) NOT NULL,
  `referencia` varchar(50) DEFAULT NULL,
  `tipo` enum('EGRESO','INGRESO') NOT NULL,
  `tipo_caja` enum('BANCARIA','COMPENSACION','FISICA') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`comprobantes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `estado_pago` enum('COBRADO','PARCIAL','PENDIENTE','VENCIDO') NOT NULL,
  `fecha_cobro` date DEFAULT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `fecha_emision` date NOT NULL,
  `fecha_vencimiento` date DEFAULT NULL,
  `monto` decimal(12,2) NOT NULL,
  `monto_pagado` decimal(12,2) NOT NULL,
  `nro_comprobante` varchar(30) NOT NULL,
  `nro_pedido` varchar(30) NOT NULL,
  `observaciones` varchar(255) DEFAULT NULL,
  `odontologo_id` bigint NOT NULL,
  `odontologo_nombre` varchar(150) NOT NULL,
  `pedido_id` bigint NOT NULL,
  `trabajo` varchar(200) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK4cid5ps66t4wfnrcnxyjb6lct` (`nro_comprobante`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`configuracion_catalogo_publico` (
  `id` bigint NOT NULL,
  `fecha_modificacion` datetime(6) DEFAULT NULL,
  `habilitado` bit(1) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`configuracion_sueldo` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activo` bit(1) NOT NULL,
  `empleado_id` bigint NOT NULL,
  `empleado_nombre` varchar(150) NOT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `fecha_modificacion` datetime(6) DEFAULT NULL,
  `frecuencia` enum('DIARIO','MENSUAL','QUINCENAL','SEMANAL') NOT NULL,
  `monto_base` decimal(12,2) NOT NULL,
  `rol` varchar(30) DEFAULT NULL,
  `saldo_devengado` decimal(12,2) NOT NULL,
  `saldo_sobrante` decimal(12,2) NOT NULL,
  `telefono` varchar(30) DEFAULT NULL,
  `ultimo_devengo_calculado` date DEFAULT NULL,
  `ultimo_pago` date DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKl2qx5242tcwjoew93eoyxlmqv` (`empleado_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`documentos_pedido` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content_type` varchar(100) DEFAULT NULL,
  `fecha_subida` datetime(6) NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `object_key` varchar(500) NOT NULL,
  `pedido_id` bigint NOT NULL,
  `subido_por` varchar(150) DEFAULT NULL,
  `tamanio_bytes` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`escaneos_pedido` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content_type` varchar(100) DEFAULT NULL,
  `descripcion` varchar(255) DEFAULT NULL,
  `fecha_subida` datetime(6) NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `object_key` varchar(500) NOT NULL,
  `pedido_id` bigint NOT NULL,
  `subido_por` varchar(150) DEFAULT NULL,
  `tamanio_bytes` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`materiales` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activo` bit(1) NOT NULL,
  `categoria` enum('ACRILICO','ADHESIVO','ALAMBRE','CERA','CERAMICA','CONSUMIBLE','HERRAMIENTA','METAL','OTRO','PORCELANA','RESINA','YESO','ZIRCONIA') NOT NULL,
  `descripcion` text,
  `descuenta_stock` bit(1) NOT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `fecha_modificacion` datetime(6) DEFAULT NULL,
  `nombre` varchar(200) NOT NULL,
  `precio_unitario` decimal(12,2) DEFAULT NULL,
  `proveedor` varchar(100) DEFAULT NULL,
  `stock_actual` double NOT NULL,
  `stock_minimo` double NOT NULL,
  `unidad_medida` varchar(20) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`movimientos_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cantidad` double NOT NULL,
  `fecha_movimiento` datetime(6) NOT NULL,
  `motivo` varchar(255) DEFAULT NULL,
  `pedido_id` bigint DEFAULT NULL,
  `stock_resultante` double NOT NULL,
  `tipo` enum('AJUSTE','ENTRADA','SALIDA') NOT NULL,
  `material_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK9i23u4y53mxiltwye4bn7bo47` (`material_id`),
  CONSTRAINT `FK9i23u4y53mxiltwye4bn7bo47` FOREIGN KEY (`material_id`) REFERENCES `materiales` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`odontologos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activo` bit(1) NOT NULL,
  `clinica` varchar(200) DEFAULT NULL,
  `cuit` varchar(13) DEFAULT NULL,
  `direccion` varchar(250) DEFAULT NULL,
  `dni` varchar(10) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `fecha_modificacion` datetime(6) DEFAULT NULL,
  `matricula` varchar(30) DEFAULT NULL,
  `nombre` varchar(150) NOT NULL,
  `telefono` varchar(30) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKgfwfyiada2erqm70bwda1og49` (`cuit`),
  UNIQUE KEY `UK7gt5c1t9oyr1ol4fi8dax9jhs` (`dni`),
  KEY `idx_odontologo_nombre` (`nombre`),
  KEY `idx_odontologo_dni` (`dni`),
  KEY `idx_odontologo_cuit` (`cuit`),
  KEY `idx_odontologo_matricula` (`matricula`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`pagos_cuenta_corriente` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `fecha` date NOT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `medio` enum('EFECTIVO','TRANSFERENCIA') NOT NULL,
  `monto` decimal(12,2) NOT NULL,
  `monto_imputado` decimal(12,2) NOT NULL,
  `nota` varchar(255) DEFAULT NULL,
  `odontologo_id` bigint NOT NULL,
  `odontologo_nombre` varchar(150) NOT NULL,
  `registrado_por` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`pagos_sueldo` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cargado_por_nombre` varchar(150) DEFAULT NULL,
  `cargado_por_telefono` varchar(30) DEFAULT NULL,
  `comprobante_url` varchar(400) DEFAULT NULL,
  `emisor` varchar(150) DEFAULT NULL,
  `empleado_id` bigint NOT NULL,
  `empleado_nombre` varchar(150) NOT NULL,
  `fecha` date NOT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `grupo_origen` varchar(100) DEFAULT NULL,
  `id_operacion` varchar(60) DEFAULT NULL,
  `manejo_sobrante` enum('CUBRE_LAB','DESCONTAR_PROXIMO','DEVUELVE_EMPLEADO') DEFAULT NULL,
  `monto` decimal(12,2) NOT NULL,
  `monto_excedente` decimal(12,2) DEFAULT NULL,
  `nota` varchar(300) DEFAULT NULL,
  `origen` enum('BOT_WHATSAPP','MANUAL') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`pedidos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `catalogo_trabajo_id` bigint DEFAULT NULL,
  `comprobante_generado` bit(1) NOT NULL,
  `estado` enum('CANCELADO','CONTROL','ENTREGADO','EN_PROCESO','LISTO','RECIBIDO') NOT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `fecha_entrega` date NOT NULL,
  `fecha_entrega_real` date DEFAULT NULL,
  `fecha_stock_consumido` datetime(6) DEFAULT NULL,
  `fecha_ultima_modificacion` datetime(6) DEFAULT NULL,
  `nro_pedido` varchar(20) NOT NULL,
  `observaciones` text,
  `observaciones_entrega` text,
  `odontologo_id` bigint NOT NULL,
  `odontologo_nombre` varchar(150) NOT NULL,
  `paciente` varchar(150) NOT NULL,
  `precio_acordado` decimal(12,2) DEFAULT NULL,
  `prioridad` enum('NORMAL','URGENTE') NOT NULL,
  `retirado_por` varchar(150) DEFAULT NULL,
  `stock_consumido` bit(1) NOT NULL,
  `tecnico_id` bigint DEFAULT NULL,
  `tecnico_nombre` varchar(150) DEFAULT NULL,
  `trabajo` varchar(200) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK4sm4vaxrdnd1r36l299qxekmi` (`nro_pedido`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`proveedores` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activo` bit(1) NOT NULL,
  `cuit` varchar(20) DEFAULT NULL,
  `direccion` varchar(300) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `nombre` varchar(200) NOT NULL,
  `telefono` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`deudas_proveedores` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `descripcion` varchar(300) NOT NULL,
  `estado` enum('PAGADO','PARCIAL','PENDIENTE') NOT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  `fecha_pago` date DEFAULT NULL,
  `fecha_vencimiento` date DEFAULT NULL,
  `monto` decimal(12,2) NOT NULL,
  `monto_pagado` decimal(12,2) NOT NULL,
  `nro_factura_proveedor` varchar(50) DEFAULT NULL,
  `observaciones` varchar(300) DEFAULT NULL,
  `proveedor_id` bigint NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `FK601ioauw5jfcm2ug69v69n83h` (`proveedor_id`),
  CONSTRAINT `FK601ioauw5jfcm2ug69v69n83h` FOREIGN KEY (`proveedor_id`) REFERENCES `proveedores` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`registros_pago_bot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cargado_por_nombre` varchar(150) DEFAULT NULL,
  `cargado_por_telefono` varchar(30) DEFAULT NULL,
  `comprobante_url` varchar(300) DEFAULT NULL,
  `emisor` varchar(200) DEFAULT NULL,
  `estado` enum('DUPLICADO','PENDIENTE','RECHAZADO','REGISTRADO') NOT NULL,
  `fecha_hora` datetime(6) NOT NULL,
  `fuente` enum('EFECTIVO','TRANSFERENCIA') NOT NULL,
  `grupo_origen` varchar(150) DEFAULT NULL,
  `id_operacion` varchar(60) DEFAULT NULL,
  `mensaje` varchar(300) DEFAULT NULL,
  `monto` decimal(12,2) DEFAULT NULL,
  `receptor_id` bigint DEFAULT NULL,
  `receptor_nombre` varchar(200) DEFAULT NULL,
  `receptor_resuelto` varchar(200) DEFAULT NULL,
  `tipo_receptor` enum('DESCONOCIDO','EMPLEADO','PROVEEDOR') DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`reporte_mensual` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `anio` int NOT NULL,
  `automatico` bit(1) NOT NULL,
  `generado_en` datetime(6) NOT NULL,
  `mes` int NOT NULL,
  `nombre_archivo` varchar(120) NOT NULL,
  `object_name` varchar(300) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKc12yylit3h5jerbdatvltljfm` (`anio`,`mes`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`tipos_trabajo` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activo` bit(1) NOT NULL,
  `categoria` enum('ATM','FIJA','ORTODONCIA','PERSONALIZADO','REMOVIBLE') NOT NULL,
  `descripcion` text,
  `fecha_creacion` datetime(6) NOT NULL,
  `fecha_modificacion` datetime(6) DEFAULT NULL,
  `foto_url` text,
  `nombre` varchar(200) NOT NULL,
  `precio` decimal(12,2) DEFAULT NULL,
  `tiempo_estimado_dias` int DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`ingredientes_receta` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cantidad` decimal(12,3) NOT NULL,
  `material_id` bigint NOT NULL,
  `material_nombre` varchar(200) NOT NULL,
  `notas` text,
  `unidad` varchar(30) DEFAULT NULL,
  `tipo_trabajo_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKr1xhqm8tdm6amg22fo4vdd6sr` (`tipo_trabajo_id`),
  CONSTRAINT `FKr1xhqm8tdm6amg22fo4vdd6sr` FOREIGN KEY (`tipo_trabajo_id`) REFERENCES `tipos_trabajo` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`usuarios` (
  `debe_cambiar_password` bit(1) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `pendiente_aprobacion` bit(1) NOT NULL,
  `terminos_aceptados` bit(1) NOT NULL,
  `fecha_aceptacion_terminos` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `telefono` varchar(30) DEFAULT NULL,
  `apellido` varchar(255) DEFAULT NULL,
  `nombre` varchar(255) DEFAULT NULL,
  `password` varchar(255) NOT NULL,
  `username` varchar(255) NOT NULL,
  `rol` enum('ADMIN','ADMINISTRATIVO','ODONTOLOGO','TECNICO') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKm2dvbwfge291euvmk6vkkocao` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
