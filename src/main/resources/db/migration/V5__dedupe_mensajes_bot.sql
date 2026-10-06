-- ═══════════════════════════════════════════════════════════════════════════
-- V5: el sistema recuerda qué mensajes de WhatsApp ya procesó el bot.
--
-- Antes la única memoria de "ya procesé este mensaje" vivía en un archivo del
-- contenedor del bot: si se perdía el volumen o el bot se reinstalaba, la
-- reconciliación volvía a registrar comprobantes ya cargados. Ahora cada registro
-- guarda el ID del mensaje de WhatsApp (el del comprobante y, si vino aparte, el
-- del pie "Emisor (Receptor)") y un hash SHA-256 del archivo. Con eso:
--   - un mensaje ya registrado se reconoce aunque el bot haya perdido su estado;
--   - el mismo archivo subido dos veces se detecta aunque no se lea el N° de operación;
--   - el bot puede preguntar "¿cuáles de estos mensajes ya están?" antes de gastar OCR.
-- ═══════════════════════════════════════════════════════════════════════════

alter table gs_auth.registros_pago_bot
    add column id_mensaje_wa  varchar(128) null,
    add column id_mensaje_pie varchar(128) null,
    add column hash_comprobante char(64)   null;

create unique index uk_registros_bot_id_mensaje_wa on gs_auth.registros_pago_bot (id_mensaje_wa);
create index ix_registros_bot_id_mensaje_pie on gs_auth.registros_pago_bot (id_mensaje_pie);
create index ix_registros_bot_hash on gs_auth.registros_pago_bot (hash_comprobante);
