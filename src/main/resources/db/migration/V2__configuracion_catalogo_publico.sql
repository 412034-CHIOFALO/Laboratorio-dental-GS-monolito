-- ═══════════════════════════════════════════════════════════════════════
-- V2: catálogo público — un solo toggle (fila única, id=1 fijo) que el
-- ADMIN prende o apaga desde /dashboard/configuracion, para decidir si
-- /catalogo (público, sin login) muestra la lista de trabajos con precio
-- (nunca la receta de materiales — ver TipoTrabajoPublicoResponse).
-- Arranca en false: no se expone nada hasta que el ADMIN lo prenda a propósito.
-- ═══════════════════════════════════════════════════════════════════════

create table gs_catalogo.configuracion_catalogo_publico (id bigint not null, habilitado bit not null, fecha_modificacion datetime(6), primary key (id)) engine=InnoDB;
insert into gs_catalogo.configuracion_catalogo_publico (id, habilitado, fecha_modificacion) values (1, false, now());
