-- ═══════════════════════════════════════════════════════════════════════════
-- V9: los escaneos 3D se revisan por dentro al subirse.
--
-- Antes un escaneo se guardaba con solo mirar la extensión: un STL cortado, un archivo de texto renombrado o una
-- malla con coordenadas inválidas entraban igual y recién fallaban cuando el técnico los abría. Ahora el backend
-- los inspecciona (MallaInspector) y guarda lo que midió:
--   - formato real (STL_BINARIO, STL_ASCII, OBJ, PLY_ASCII, PLY_BINARIO);
--   - cantidad de triángulos y de vértices, y las dimensiones del modelo (mm);
--   - el SHA-256 del archivo, para no cargar dos veces el mismo escaneo en un pedido;
--   - avisos (unidades raras, triángulos degenerados...), que no bloquean pero se muestran.
-- Las filas anteriores quedan con estas columnas en NULL.
-- ═══════════════════════════════════════════════════════════════════════════

alter table gs_auth.escaneos_pedido
    add column formato varchar(20) null,
    add column triangulos bigint null,
    add column vertices bigint null,
    add column dim_x_mm decimal(14,3) null,
    add column dim_y_mm decimal(14,3) null,
    add column dim_z_mm decimal(14,3) null,
    add column sha256 char(64) null,
    add column advertencias varchar(600) null;

create index ix_escaneos_pedido_sha256 on gs_auth.escaneos_pedido (pedido_id, sha256);
