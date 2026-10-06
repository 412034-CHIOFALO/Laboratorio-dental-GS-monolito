-- ═══════════════════════════════════════════════════════════════════════════
-- V6: "ya avisé que este pedido está listo".
--
-- Antes, cada vez que un pedido pasaba a LISTO se mandaba el WhatsApp al odontólogo, aunque ya se
-- le hubiera avisado (LISTO -> otro estado -> LISTO por un error de carga). Ahora el pedido
-- recuerda cuándo el bot ACEPTÓ el aviso; si el bot estaba caído o el número no tenía WhatsApp
-- la columna queda en NULL y el aviso se reintenta la próxima vez que pase a LISTO.
-- ═══════════════════════════════════════════════════════════════════════════

alter table gs_auth.pedidos add column notificado_listo_en datetime(6) null;
