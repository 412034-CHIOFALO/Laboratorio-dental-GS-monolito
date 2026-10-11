-- ═══════════════════════════════════════════════════════════════════════════
-- V7: semáforo de comprobantes — por qué un pago quedó "pendiente de revisión".
--
-- Un comprobante que el sistema no puede dar por bueno (nombres que no coinciden, sin N° de operación,
-- sin fecha o muy vieja, monto alto, rastros de edición...) ya no se registra solo: queda PENDIENTE hasta que
-- un administrativo lo apruebe o lo rechace viendo la imagen. Esta columna guarda los motivos (separados
-- por " | ") para mostrárselos a quien revisa.
-- ═══════════════════════════════════════════════════════════════════════════

alter table gs_auth.registros_pago_bot add column motivos_revision varchar(600) null;
