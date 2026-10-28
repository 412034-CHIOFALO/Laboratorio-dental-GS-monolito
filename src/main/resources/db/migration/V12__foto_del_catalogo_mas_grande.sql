-- ═══════════════════════════════════════════════════════════════════════════
-- V12: la foto de un trabajo del catálogo se guarda como texto (la imagen en base64) en tipos_trabajo.foto_url, y esa
-- columna era TEXT: 64 KB como máximo. Una foto de celular, aun achicada y recomprimida, pesa de 100 a 300 KB, así que
-- no entraba y el guardado fallaba. MEDIUMTEXT admite hasta 16 MB. No toca los datos que ya hay.
-- ═══════════════════════════════════════════════════════════════════════════

alter table gs_auth.tipos_trabajo
    modify column foto_url mediumtext null;
