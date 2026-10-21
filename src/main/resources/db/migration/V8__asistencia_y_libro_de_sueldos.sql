-- ═══════════════════════════════════════════════════════════════════════════
-- V8: sueldos por mes calendario, con asistencia, vacaciones y libro de movimientos.
--
-- Antes el devengo era "monto / 30 días fijos" y corría solo a las 00:05: un año devengaba 5 días de
-- sueldo de más, no se podía descontar una ausencia ni registrar vacaciones, y el saldo se pisaba sin
-- dejar historia. Ahora:
--   - el sueldo se reparte por los días REALES del mes (un mensual devenga exactamente su monto por mes);
--   - cada empleado tiene un calendario de asistencia (trabajó, ausente, vacaciones, feriado, licencia);
--   - todo cambio del saldo (devengo, ajuste por asistencia, ajuste manual, pago) queda en un libro;
--   - el empleado tiene fecha de alta y de baja, y un cupo anual de vacaciones.
-- ═══════════════════════════════════════════════════════════════════════════

alter table gs_auth.configuracion_sueldo
    add column fecha_alta date null,
    add column fecha_baja date null,
    add column dias_vacaciones_anuales int not null default 14,
    -- fracción de centavo que todavía no se acreditó (el devengo diario es 1/30 de un monto, no siempre exacto)
    add column resto_devengo decimal(14,10) not null default 0;

update gs_auth.configuracion_sueldo set fecha_alta = date(fecha_creacion) where fecha_alta is null;

alter table gs_auth.configuracion_sueldo modify column fecha_alta date not null;

create table if not exists gs_auth.`asistencia_empleado` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `empleado_id` bigint NOT NULL,
  `fecha` date NOT NULL,
  `tipo` enum('AUSENTE','FERIADO','LICENCIA_PAGA','TRABAJADO','VACACIONES') NOT NULL,
  `nota` varchar(200) DEFAULT NULL,
  `cargado_por` varchar(100) DEFAULT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asistencia_empleado_fecha` (`empleado_id`,`fecha`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

create table if not exists gs_auth.`movimientos_sueldo` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `empleado_id` bigint NOT NULL,
  `fecha` date NOT NULL,
  `tipo` enum('AJUSTE_ASISTENCIA','AJUSTE_MANUAL','DEVENGO','PAGO') NOT NULL,
  `monto` decimal(12,2) NOT NULL,
  `saldo_devengado` decimal(12,2) NOT NULL,
  `saldo_sobrante` decimal(12,2) NOT NULL,
  `detalle` varchar(300) DEFAULT NULL,
  `usuario` varchar(100) DEFAULT NULL,
  `fecha_creacion` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_movimientos_sueldo_empleado` (`empleado_id`,`fecha_creacion`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
