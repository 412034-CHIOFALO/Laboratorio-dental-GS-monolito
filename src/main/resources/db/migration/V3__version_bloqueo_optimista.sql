-- ═══════════════════════════════════════════════════════════════════════
-- V3: bloqueo optimista (@Version) en las entidades donde dos escrituras
-- simultáneas se pisaban sin que nadie se enterara — dos personas cargando un
-- pago al mismo odontólogo, el bot y el panel tocando el mismo sueldo, dos
-- movimientos de stock del mismo material, etc. Con esto, la segunda escritura
-- falla con 409 ("otro usuario modificó este registro") en vez de pisar.
-- Las filas existentes arrancan en versión 0.
-- ═══════════════════════════════════════════════════════════════════════

alter table gs_pedidos.pedidos               add column version bigint not null default 0;
alter table gs_finanzas.comprobantes         add column version bigint not null default 0;
alter table gs_stock.materiales              add column version bigint not null default 0;
alter table gs_finanzas.configuracion_sueldo add column version bigint not null default 0;
alter table gs_finanzas.registros_pago_bot   add column version bigint not null default 0;
alter table gs_finanzas.deudas_proveedores   add column version bigint not null default 0;
