-- ═══════════════════════════════════════════════════════════════════════
-- V1: baseline. DDL exportado directo de las entidades JPA reales
-- (jakarta.persistence.schema-generation.scripts.action=create contra el
-- estado real del código) — no escrito a mano. Se le agregó el prefijo de
-- schema en cada tabla: Hibernate no lo incluye en el script exportado,
-- aunque sí lo respeta al ejecutar DDL en vivo (ddl-auto=update, como se
-- usó hasta ahora).
--
-- De acá en más Hibernate queda en modo "validate" (ver application.properties)
-- — ya no crea ni modifica tablas solo. Cualquier cambio de esquema futuro
-- es una migración nueva (V2, V3, ...), nunca una edición de este archivo.
-- ═══════════════════════════════════════════════════════════════════════

-- ── gs_auth ──────────────────────────────────────────────────────────
create table gs_auth.auditoria_eventos (id bigint not null auto_increment, timestamp datetime(6) not null, tipo varchar(50) not null, usuario varchar(100) not null, accion varchar(200) not null, entidad varchar(200) not null, detalle varchar(500), primary key (id)) engine=InnoDB;
create table gs_auth.usuarios (debe_cambiar_password bit not null, enabled bit not null, pendiente_aprobacion bit not null, terminos_aceptados bit not null, fecha_aceptacion_terminos datetime(6), id bigint not null auto_increment, telefono varchar(30), apellido varchar(255), nombre varchar(255), password varchar(255) not null, username varchar(255) not null, rol enum ('ADMIN','ADMINISTRATIVO','ODONTOLOGO','TECNICO') not null, primary key (id)) engine=InnoDB;
alter table gs_auth.usuarios add constraint UKm2dvbwfge291euvmk6vkkocao unique (username);

-- ── gs_catalogo ──────────────────────────────────────────────────────
create table gs_catalogo.tipos_trabajo (activo bit not null, precio decimal(12,2), tiempo_estimado_dias integer, fecha_creacion datetime(6) not null, fecha_modificacion datetime(6), id bigint not null auto_increment, nombre varchar(200) not null, descripcion TEXT, foto_url TEXT, categoria enum ('ATM','FIJA','ORTODONCIA','PERSONALIZADO','REMOVIBLE') not null, primary key (id)) engine=InnoDB;
create table gs_catalogo.ingredientes_receta (cantidad decimal(12,3) not null, id bigint not null auto_increment, material_id bigint not null, tipo_trabajo_id bigint not null, unidad varchar(30), material_nombre varchar(200) not null, notas TEXT, primary key (id)) engine=InnoDB;
alter table gs_catalogo.ingredientes_receta add constraint FKr1xhqm8tdm6amg22fo4vdd6sr foreign key (tipo_trabajo_id) references gs_catalogo.tipos_trabajo (id);

-- ── gs_finanzas ──────────────────────────────────────────────────────
create table gs_finanzas.caja_movimientos (fecha_movimiento date not null, monto decimal(12,2) not null, fecha_creacion datetime(6) not null, id bigint not null auto_increment, creado_por varchar(50), referencia varchar(50), concepto varchar(300) not null, tipo enum ('EGRESO','INGRESO') not null, tipo_caja enum ('BANCARIA','COMPENSACION','FISICA') not null, primary key (id)) engine=InnoDB;
create table gs_finanzas.comprobantes (fecha_cobro date, fecha_emision date not null, fecha_vencimiento date, monto decimal(12,2) not null, monto_pagado decimal(12,2) not null, fecha_creacion datetime(6) not null, id bigint not null auto_increment, odontologo_id bigint not null, pedido_id bigint not null, nro_comprobante varchar(30) not null, nro_pedido varchar(30) not null, odontologo_nombre varchar(150) not null, trabajo varchar(200) not null, observaciones varchar(255), estado_pago enum ('COBRADO','PARCIAL','PENDIENTE','VENCIDO') not null, primary key (id)) engine=InnoDB;
alter table gs_finanzas.comprobantes add constraint UK4cid5ps66t4wfnrcnxyjb6lct unique (nro_comprobante);
create table gs_finanzas.configuracion_sueldo (activo bit not null, monto_base decimal(12,2) not null, saldo_devengado decimal(12,2) not null, saldo_sobrante decimal(12,2) not null, ultimo_devengo_calculado date, ultimo_pago date, empleado_id bigint not null, fecha_creacion datetime(6) not null, fecha_modificacion datetime(6), id bigint not null auto_increment, rol varchar(30), telefono varchar(30), empleado_nombre varchar(150) not null, frecuencia enum ('DIARIO','MENSUAL','QUINCENAL','SEMANAL') not null, primary key (id)) engine=InnoDB;
alter table gs_finanzas.configuracion_sueldo add constraint UKl2qx5242tcwjoew93eoyxlmqv unique (empleado_id);
create table gs_finanzas.deudas_proveedores (fecha_pago date, fecha_vencimiento date, monto decimal(12,2) not null, monto_pagado decimal(12,2) not null, fecha_creacion datetime(6) not null, id bigint not null auto_increment, proveedor_id bigint not null, nro_factura_proveedor varchar(50), descripcion varchar(300) not null, observaciones varchar(300), estado enum ('PAGADO','PARCIAL','PENDIENTE') not null, primary key (id)) engine=InnoDB;
create table gs_finanzas.pagos_cuenta_corriente (fecha date not null, monto decimal(12,2) not null, monto_imputado decimal(12,2) not null, fecha_creacion datetime(6) not null, id bigint not null auto_increment, odontologo_id bigint not null, registrado_por varchar(100), odontologo_nombre varchar(150) not null, nota varchar(255), medio enum ('EFECTIVO','TRANSFERENCIA') not null, primary key (id)) engine=InnoDB;
create table gs_finanzas.pagos_sueldo (fecha date not null, monto decimal(12,2) not null, monto_excedente decimal(12,2), empleado_id bigint not null, fecha_creacion datetime(6) not null, id bigint not null auto_increment, cargado_por_telefono varchar(30), id_operacion varchar(60), grupo_origen varchar(100), cargado_por_nombre varchar(150), emisor varchar(150), empleado_nombre varchar(150) not null, nota varchar(300), comprobante_url varchar(400), manejo_sobrante enum ('CUBRE_LAB','DESCONTAR_PROXIMO','DEVUELVE_EMPLEADO'), origen enum ('BOT_WHATSAPP','MANUAL') not null, primary key (id)) engine=InnoDB;
create table gs_finanzas.proveedores (activo bit not null, fecha_creacion datetime(6) not null, id bigint not null auto_increment, cuit varchar(20), telefono varchar(20), email varchar(100), nombre varchar(200) not null, direccion varchar(300), primary key (id)) engine=InnoDB;
alter table gs_finanzas.deudas_proveedores add constraint FK601ioauw5jfcm2ug69v69n83h foreign key (proveedor_id) references gs_finanzas.proveedores (id);
create table gs_finanzas.registros_pago_bot (monto decimal(12,2), fecha_hora datetime(6) not null, id bigint not null auto_increment, receptor_id bigint, cargado_por_telefono varchar(30), id_operacion varchar(60), cargado_por_nombre varchar(150), grupo_origen varchar(150), emisor varchar(200), receptor_nombre varchar(200), receptor_resuelto varchar(200), comprobante_url varchar(300), mensaje varchar(300), estado enum ('DUPLICADO','PENDIENTE','RECHAZADO','REGISTRADO') not null, fuente enum ('EFECTIVO','TRANSFERENCIA') not null, tipo_receptor enum ('DESCONOCIDO','EMPLEADO','PROVEEDOR'), primary key (id)) engine=InnoDB;
create table gs_finanzas.reporte_mensual (anio integer not null, automatico bit not null, mes integer not null, generado_en datetime(6) not null, id bigint not null auto_increment, nombre_archivo varchar(120) not null, object_name varchar(300) not null, primary key (id)) engine=InnoDB;
alter table gs_finanzas.reporte_mensual add constraint UKc12yylit3h5jerbdatvltljfm unique (anio, mes);

-- ── gs_pedidos ───────────────────────────────────────────────────────
create table gs_pedidos.odontologos (activo bit not null, fecha_creacion datetime(6) not null, fecha_modificacion datetime(6), id bigint not null auto_increment, dni varchar(10), cuit varchar(13), matricula varchar(30), telefono varchar(30), email varchar(100), nombre varchar(150) not null, clinica varchar(200), direccion varchar(250), primary key (id)) engine=InnoDB;
create index idx_odontologo_nombre on gs_pedidos.odontologos (nombre);
create index idx_odontologo_dni on gs_pedidos.odontologos (dni);
create index idx_odontologo_cuit on gs_pedidos.odontologos (cuit);
create index idx_odontologo_matricula on gs_pedidos.odontologos (matricula);
alter table gs_pedidos.odontologos add constraint UK7gt5c1t9oyr1ol4fi8dax9jhs unique (dni);
alter table gs_pedidos.odontologos add constraint UKgfwfyiada2erqm70bwda1og49 unique (cuit);
create table gs_pedidos.pedidos (comprobante_generado bit not null, fecha_entrega date not null, fecha_entrega_real date, precio_acordado decimal(12,2), stock_consumido bit not null, catalogo_trabajo_id bigint, fecha_creacion datetime(6) not null, fecha_stock_consumido datetime(6), fecha_ultima_modificacion datetime(6), id bigint not null auto_increment, odontologo_id bigint not null, tecnico_id bigint, nro_pedido varchar(20) not null, odontologo_nombre varchar(150) not null, paciente varchar(150) not null, retirado_por varchar(150), tecnico_nombre varchar(150), trabajo varchar(200) not null, observaciones TEXT, observaciones_entrega TEXT, estado enum ('CANCELADO','CONTROL','ENTREGADO','EN_PROCESO','LISTO','RECIBIDO') not null, prioridad enum ('NORMAL','URGENTE') not null, primary key (id)) engine=InnoDB;
alter table gs_pedidos.pedidos add constraint UK4sm4vaxrdnd1r36l299qxekmi unique (nro_pedido);
create table gs_pedidos.documentos_pedido (fecha_subida datetime(6) not null, id bigint not null auto_increment, pedido_id bigint not null, tamanio_bytes bigint, content_type varchar(100), subido_por varchar(150), object_key varchar(500) not null, file_name varchar(255) not null, primary key (id)) engine=InnoDB;
create table gs_pedidos.escaneos_pedido (fecha_subida datetime(6) not null, id bigint not null auto_increment, pedido_id bigint not null, tamanio_bytes bigint, content_type varchar(100), subido_por varchar(150), object_key varchar(500) not null, descripcion varchar(255), file_name varchar(255) not null, primary key (id)) engine=InnoDB;

-- ── gs_stock ─────────────────────────────────────────────────────────
create table gs_stock.materiales (activo bit not null, descuenta_stock bit not null, precio_unitario decimal(12,2), stock_actual float(53) not null, stock_minimo float(53) not null, fecha_creacion datetime(6) not null, fecha_modificacion datetime(6), id bigint not null auto_increment, unidad_medida varchar(20) not null, proveedor varchar(100), nombre varchar(200) not null, descripcion TEXT, categoria enum ('ACRILICO','ADHESIVO','ALAMBRE','CERA','CERAMICA','CONSUMIBLE','HERRAMIENTA','METAL','OTRO','PORCELANA','RESINA','YESO','ZIRCONIA') not null, primary key (id)) engine=InnoDB;
create table gs_stock.movimientos_stock (cantidad float(53) not null, stock_resultante float(53) not null, fecha_movimiento datetime(6) not null, id bigint not null auto_increment, material_id bigint not null, pedido_id bigint, motivo varchar(255), tipo enum ('AJUSTE','ENTRADA','SALIDA') not null, primary key (id)) engine=InnoDB;
alter table gs_stock.movimientos_stock add constraint FK9i23u4y53mxiltwye4bn7bo47 foreign key (material_id) references gs_stock.materiales (id);
