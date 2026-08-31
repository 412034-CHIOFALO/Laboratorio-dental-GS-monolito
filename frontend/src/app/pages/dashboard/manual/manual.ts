import { Component, signal } from '@angular/core';

interface Captura {
  /** id estable para reemplazar el placeholder por la imagen real. */
  id: string;
  /** qué debe mostrar la captura y qué resaltar. */
  descripcion: string;
}

interface Seccion {
  id: string;
  titulo: string;
  /** agrupa las secciones en el menú lateral (encabezados tipo "Solo Admin"). */
  grupo: string;
  intro: string;
  queVes?: string[];
  queHaces?: string[];
  /** pasos numerados, botón por botón, para el flujo principal de la pantalla. */
  pasos?: string[];
  flujo?: string[];
  captura?: Captura;
}

@Component({
  selector: 'app-manual',
  standalone: true,
  imports: [],
  templateUrl: './manual.html',
  styleUrls: ['./manual.css'],
})
export class ManualComponent {

  readonly activa = signal('intro');

  /** ids de captura cuya imagen (/manual/<id>.png) cargó OK. */
  readonly conImagen = signal<Set<string>>(new Set());

  /** grupos en el orden en que se muestran en el menú lateral. */
  readonly grupos = [
    'Bienvenida',
    'Para todo el equipo (Técnico, Administrativo y Admin)',
    'Solo Administrativo y Admin',
    'Solo Admin',
    'Tu cuenta',
  ];

  seleccionar(id: string): void {
    this.activa.set(id);
    document.querySelector('.manual-contenido')?.scrollTo({ top: 0, behavior: 'smooth' });
  }

  /** La imagen existe → la mostramos y ocultamos el placeholder. */
  marcarImagen(id: string): void {
    this.conImagen.update(s => new Set(s).add(id));
  }

  seccionesDe(grupo: string): Seccion[] {
    return this.secciones.filter(s => s.grupo === grupo);
  }

  readonly secciones: Seccion[] = [
    // ─────────────────────────── BIENVENIDA ───────────────────────────
    {
      id: 'intro',
      titulo: 'Bienvenida',
      grupo: 'Bienvenida',
      intro: 'Este manual explica, pantalla por pantalla y paso a paso, qué ves, qué podés hacer y cómo se conecta cada cosa con el resto del sistema. Está ordenado por rol: primero lo que ve todo el equipo, después lo que se suma para Administrativo y Admin, y al final lo exclusivo de Admin.',
      queVes: [
        'A la izquierda, el menú con todas las secciones del sistema, agrupadas por Operativo, Gestión, Archivo y Administración — vos solo ves los ítems que tu rol tiene permitido.',
        'Arriba a la derecha, el tema claro/oscuro, la campanita de notificaciones y tu perfil.',
      ],
      queHaces: [
        'Navegás por el menú lateral; cada ítem es una pantalla explicada acá.',
        'Si es tu primera vez, conviene arrancar por el Tour guiado (menú de perfil → Tour guiado, o el botón "Tour guiado" al pie del menú).',
      ],
    },
    {
      id: 'roles',
      titulo: 'Roles y qué desbloquea cada uno',
      grupo: 'Bienvenida',
      intro: 'Hay 4 roles. Los permisos son acumulativos: Administrativo tiene todo lo de Técnico más algunas pantallas extra, y Admin tiene todo lo de Administrativo más Auditoría.',
      queVes: [
        'Técnico: Inicio, Pedidos, Producción, Entregas, Catálogo, Odontólogos, Stock, Documentos, Escaneos 3D, Manual, Preguntas frecuentes y Mi perfil. No ve Finanzas, Proveedores, Bot WhatsApp, Reportes, Usuarios ni Auditoría, y en la ficha de un odontólogo no ve su saldo/deuda.',
        'Administrativo: todo lo del Técnico + Finanzas (Cajas, Cuentas corrientes, Triangulados, Sueldos), Proveedores, Bot WhatsApp, Reportes y Usuarios (puede crear y activar cuentas). No ve Auditoría.',
        'Admin: todo lo de Administrativo + Auditoría. Puede crear usuarios pero, a propósito, no puede activarlos él mismo — eso lo hace un Administrativo, como doble control.',
        'Odontólogo: es un rol que existe en el sistema pero los odontólogos no usan este panel — se comunican por WhatsApp/email; el laboratorio carga sus datos y pedidos por ellos.',
      ],
      flujo: ['Si te falta ver algo que otro compañero sí ve, lo más probable es que sea justamente por tu rol — pedile a un Admin o Administrativo que revise tu usuario en la sección Usuarios.'],
    },

    // ─────────────────── PARA TODO EL EQUIPO ───────────────────
    {
      id: 'inicio',
      titulo: 'Inicio (panel del día)',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'Es la pantalla a la que volvés siempre: un resumen del estado del laboratorio hoy.',
      queVes: ['Tarjetas con pedidos en curso, atrasados y entregas pendientes.', 'Accesos rápidos a los módulos que más usás.'],
      queHaces: ['Mirás de un vistazo qué requiere atención y saltás directo al módulo con un clic.'],
      captura: { id: 'cap-inicio', descripcion: 'Pantalla de Inicio completa, mostrando las tarjetas de resumen y los accesos rápidos.' },
    },
    {
      id: 'pedidos',
      titulo: 'Pedidos',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'El corazón del sistema: acá cargás cada trabajo que pide un odontólogo y seguís su estado.',
      queVes: [
        'La tabla: Pedido, Estado, Paciente, Odontólogo, Trabajo, Entrega, Precio, Acciones.',
        'Badge "URGENTE" en los prioritarios, "ATRASADO" en los que llevan días hábiles sin entregar (pasá el mouse para ver cuántos), y "sin precio" en los que todavía no tienen monto acordado.',
        'Filtros por estado con contador: Todos, Recibidos, En proceso, Control, Listos, Entregados, Cancelados. Buscador por nº de pedido, paciente, odontólogo o trabajo.',
      ],
      queHaces: ['Creás, editás, cancelás y abrís el detalle de cada pedido.'],
      pasos: [
        'Tocá "Nuevo pedido" arriba a la derecha.',
        'Campo Odontólogo *: empezá a escribir el nombre, DNI, CUIT o matrícula — el sistema busca en los odontólogos existentes. Si aparece un badge "✓ Existente" lo estás reutilizando; si dice "+ Se creará nuevo" se va a dar de alta un odontólogo nuevo con ese nombre al guardar.',
        'Si es un odontólogo nuevo, opcionalmente desplegá "Completar datos del nuevo odontólogo" para cargar DNI, CUIT, Matrícula, Teléfono, Email, Clínica y Dirección (nada de esto es obligatorio, se puede completar después).',
        'Completá Paciente * y Fecha de entrega *.',
        'Campo Tipo de trabajo *: escribí y elegí uno del Catálogo (badge "✓ Catálogo") o escribí uno libre (badge "Personalizado"). Elegir uno del catálogo es lo que permite el descuento automático de stock más adelante.',
        'Elegí Prioridad: Normal o Urgente.',
        'Opcional: Precio acordado (si no lo sabés todavía, dejalo en blanco — se pedirá al momento de la entrega), Observaciones, y podés adjuntar Escaneos 3D (STL/OBJ/PLY) solo al crear, no al editar.',
        'Tocá "Guardar pedido".',
      ],
      flujo: ['Al crear el pedido, si elegís un tipo del Catálogo, queda vinculada su receta de materiales para el descuento automático de stock en Producción.'],
      captura: { id: 'cap-pedidos-lista', descripcion: 'La tabla de pedidos con al menos un atrasado y uno "sin precio" visibles.' },
    },
    {
      id: 'pedidos-editar',
      titulo: 'Pedidos: editar, cancelar y ver detalle',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'Cada fila de la tabla tiene tres acciones (los últimos dos se ocultan si el pedido ya está Entregado o Cancelado).',
      pasos: [
        'Ícono "Ver detalle": abre una ficha de solo lectura con todos los datos, el técnico asignado, y si tiene escaneos 3D adjuntos vas a ver botones "Ver en 3D" y "Descargar" para cada uno.',
        'Ícono "Editar": abre el mismo formulario que "Nuevo pedido" (sin la opción de adjuntar escaneos) con los datos ya cargados.',
        'Ícono "Cancelar pedido": pide confirmación ("¿Cancelar pedido?" — "No, volver" / "Sí, cancelar"). El pedido pasa a CANCELADO y no se puede modificar más.',
      ],
    },
    {
      id: 'produccion',
      titulo: 'Producción (Kanban)',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'El tablero del taller: movés cada trabajo por sus etapas: Recibido → En proceso → Control de calidad → Listo para entregar.',
      queVes: ['Cada tarjeta muestra nº de pedido, badge URGENTE si aplica, el trabajo, odontólogo, paciente, técnico asignado y un indicador de vencimiento ("Atrasado Xd", "Entrega hoy", "Entrega mañana", "Entrega en Xd").', 'Filtro por técnico arriba de las columnas.'],
      pasos: [
        'En computadora (pantalla ancha): arrastrá la tarjeta a la columna siguiente (drag & drop).',
        'En celular/tablet: las columnas se ven como tabs. En cada tarjeta hay un botón circular "◀" para devolver a la etapa anterior, y un botón principal cuyo texto cambia según dónde estés: "Iniciar producción" (desde Recibido), "Enviar a control" (desde En proceso), "Marcar como listo" (desde Control).',
      ],
      flujo: ['Cuando un pedido pasa a EN PROCESO, el sistema descuenta solo los materiales de la receta cargada en el Catálogo (ver Stock).', 'Cuando pasa a LISTO, queda disponible para el paso de Entregas.'],
      captura: { id: 'cap-produccion', descripcion: 'El tablero Kanban con tarjetas en varias columnas.' },
    },
    {
      id: 'entregas',
      titulo: 'Entregas',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'Cuando el trabajo está LISTO, confirmás la entrega. Acá es donde se genera la deuda del odontólogo.',
      queVes: ['Tab "Pendientes": cards de los pedidos LISTO esperando retiro, con días esperando y precio.', 'Tab "Historial": tabla de todo lo ya entregado.', 'Si hay pendientes, aparecen botones "Copiar mensaje" y "Enviar por WhatsApp" para avisar al odontólogo.'],
      pasos: [
        'En la card del pedido, tocá "Marcar como entregado".',
        'Completá ¿Quién recibió el trabajo? * (nombre del odontólogo, asistente, cadete, etc.).',
        'Revisá Monto a facturar * — viene pre-cargado con el precio del pedido, pero podés editarlo si cambió.',
        'Opcional: Fecha de entrega real (por defecto hoy) y Observaciones.',
        'Tocá "Confirmar entrega".',
      ],
      flujo: ['Al confirmar, se crea automáticamente la cuenta por cobrar del odontólogo por ese monto → aparece en su cuenta corriente y en el ranking de morosos. La entrega genera la DEUDA, no el cobro.'],
      captura: { id: 'cap-entregas-modal', descripcion: 'El modal de entrega con el campo "Monto a facturar" resaltado.' },
    },
    {
      id: 'catalogo',
      titulo: 'Catálogo',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'Tus tipos de trabajo (Hawley, placas, expansores…) con precio y receta de materiales.',
      queVes: ['Cards por trabajo con foto, categoría, precio y tiempo estimado.', 'Filtro por categoría: Todos, Prótesis Fija, Removible, Ortodoncia, ATM, Personalizado.'],
      pasos: [
        'Para crear: tocá "Nuevo trabajo". Completá Nombre *, Categoría *, Precio en ARS * (podés poner 0 para que figure "A convenir"), y opcionalmente Foto, Tiempo estimado en días y Descripción.',
        'Para cambiar el precio rápido sin abrir el formulario completo: en la card tocá el botón "Precio", editá el número y confirmá con el ✓ (o cancelá con ✕).',
        'Para armar la receta de materiales (la que después descuenta stock solo): dentro del formulario, en "Receta de materiales" tocá "Agregar" y elegí un Material (autocompleta contra Stock, mostrando cuánto hay disponible), la Cantidad y la Unidad. Podés agregar varias líneas.',
        'Guardá con "Crear trabajo" o "Guardar cambios".',
        'Para eliminar: botón "Eliminar" en la card, confirmá con "Sí, eliminar".',
      ],
      flujo: ['Esa receta es la que Producción usa para descontar stock automáticamente cuando un pedido de ese tipo pasa a EN PROCESO.'],
      captura: { id: 'cap-catalogo', descripcion: 'La grilla del catálogo con las tarjetas de trabajos.' },
    },
    {
      id: 'odontologos',
      titulo: 'Odontólogos',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'El directorio de odontólogos: sus datos de contacto y, si tu rol lo permite, su cuenta corriente.',
      queVes: ['Cards con nombre, matrícula, estado Activo/Inactivo, clínica/teléfono/email.', 'Filtro Todos / Activos / Inactivos y buscador por nombre, DNI, CUIT o matrícula.'],
      pasos: [
        'Para crear: "Nuevo odontólogo" → completá Nombre completo * (el resto — DNI, CUIT, Matrícula, Teléfono, Email, Clínica, Dirección — es opcional) → "Guardar odontólogo".',
        'Para ver la ficha completa: tocá la card. Si tu rol ve finanzas vas a ver también el saldo y el botón "Registrar pago".',
        'Para dar de baja: dentro de editar, botón "Desactivar" (no borra nada, solo lo saca de los listados activos — pedidos e historial se conservan).',
        'Para ver todo su historial de trabajos: botón "Ver trabajos" en la card, te lleva a su ficha 360.',
      ],
    },
    {
      id: 'odontologo-360',
      titulo: 'Ficha 360 de un odontólogo',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'Es la vista completa de un odontólogo: sus pedidos, y si tu rol lo permite, su cuenta corriente.',
      queVes: ['KPIs: pedidos totales, activos, entregados, último pedido (y facturado total si ves finanzas).', 'Tabs: Resumen, Pedidos, Finanzas (solo Administrativo/Admin), Escáneres y Documentos.'],
      queHaces: ['Tab Pedidos: la tabla completa de todos sus trabajos con estado y fechas.', 'Tab Finanzas (si la ves): el saldo pendiente, los trabajos activos por cobrar y el historial de entregas facturadas.'],
      captura: { id: 'cap-cuenta-corriente', descripcion: 'La ficha del odontólogo con la sección Cuenta corriente (saldo + tabla de comprobantes) y el botón "Registrar pago".' },
    },
    {
      id: 'stock',
      titulo: 'Stock',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'El inventario de materiales, con alertas cuando algo cae por debajo del mínimo que vos configuraste.',
      queVes: ['Materiales con stock actual, mínimo y estado: OK, "Bajo mínimo" o "Agotado".', 'Filtro por categoría y checkbox "Solo bajo stock".'],
      pasos: [
        'Para crear un material: "Nuevo material" → Nombre *, Categoría *, Unidad de medida *, Stock actual * y Stock mínimo (alerta) * son obligatorios; Descripción, Precio unitario y Proveedor son opcionales.',
        'Si el material es "por uso" (esmaltes, pinceles, cosas que no se cuentan por unidad exacta), desmarcá el checkbox "Se descuenta del stock al usarlo" — el sistema solo va a avisar si no hay, pero no va a restar números.',
        'Para registrar una compra o un consumo manual: botón "Movimiento" en la fila → elegí el tipo (Entrada para sumar, Salida para restar, Ajuste para fijar el stock exacto) → completá la Cantidad (o "Stock final" si es Ajuste) y un Motivo opcional → "Registrar movimiento".',
      ],
      flujo: ['Cuando el stock actual cae por debajo del mínimo configurado, el material queda marcado "Bajo mínimo" (o "Agotado" si llega a 0) y te llega la alerta por la campanita de notificaciones del sistema.'],
      captura: { id: 'cap-stock', descripcion: 'La lista de materiales con al menos uno en estado Bajo/Crítico.' },
    },
    {
      id: 'documentos',
      titulo: 'Documentos',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'Archivos generales asociados a un pedido (presupuestos, indicaciones del odontólogo, etc.) que no son escaneos 3D.',
      queVes: ['Los documentos agrupados por pedido.'],
      queHaces: ['Subís un archivo eligiendo a qué pedido corresponde y una descripción opcional.'],
    },
    {
      id: 'escaneos',
      titulo: 'Escaneos 3D',
      grupo: 'Para todo el equipo (Técnico, Administrativo y Admin)',
      intro: 'Los archivos 3D (STL/OBJ) de cada pedido, con visor incorporado.',
      queVes: ['Los escaneos adjuntos a cada pedido.'],
      queHaces: ['Subís archivos y con "Ver 3D" los previsualizás dentro del sistema (rotar, zoom) sin abrir otro programa.'],
      captura: { id: 'cap-visor-3d', descripcion: 'El visor 3D abierto mostrando un modelo.' },
    },

    // ─────────────────── SOLO ADMINISTRATIVO Y ADMIN ───────────────────
    {
      id: 'finanzas-resumen',
      titulo: 'Finanzas → Resumen',
      grupo: 'Solo Administrativo y Admin',
      intro: 'La pantalla con la que arranca Finanzas: un pantallazo de las 4 áreas (Cajas, Cuentas corrientes, Proveedores, Sueldos) sin tener que entrar a cada una.',
      queVes: [
        'Tarjeta Cajas: el total entre las 3 cajas, con el desglose Física/Bancaria.',
        'Tarjeta Cuentas corrientes: cuántos odontólogos están morosos y los 2 con más deuda, con acceso directo a "Ver todas".',
        'Tarjeta Proveedores: la deuda total con proveedores de materiales.',
        'Tarjeta Sueldos: el total de sueldos devengados pendientes de pago del período actual.',
        'Un cuadro de "Alertas activas" abajo (por ejemplo, sueldos devengados a pagar) si hay algo que requiere atención.',
      ],
      queHaces: ['Tocás cualquiera de los 4 links ("Ver movimientos", "Ver todas", "Ver proveedores", "Ver sueldos") para ir directo a esa pestaña con el detalle completo.'],
      captura: { id: 'cap-finanzas', descripcion: 'La pantalla de Resumen de Finanzas con las 4 tarjetas (Cajas, Cuentas corrientes, Proveedores, Sueldos) y el cuadro de alertas activas.' },
    },
    {
      id: 'finanzas-cajas',
      titulo: 'Finanzas → Cajas',
      grupo: 'Solo Administrativo y Admin',
      intro: 'Las 3 cajas del laboratorio: Física (efectivo), Bancaria (transferencias) y Compensación (uso interno para triangular pagos).',
      queVes: ['Una tarjeta por caja con su saldo — la de Compensación muestra una advertencia si su saldo no está en $0 (ver "Triangulados" más abajo).', 'La tabla de movimientos de la caja seleccionada, con ingresos y egresos.'],
      pasos: [
        'Elegí la caja tocando su tarjeta (Física, Bancaria o Compensación).',
        'Para cargar un movimiento a mano: "Nuevo movimiento" → elegí Ingreso o Egreso → Caja, Categoría, Concepto * y Monto * son lo mínimo; Fecha y Referencia son opcionales → "Registrar movimiento".',
        'Podés exportar "PDF cierre" (de la caja actual) o "PDF mensual".',
      ],
      flujo: ['Los pagos a cuenta corriente (efectivo→Física, transferencia→Bancaria) y los sueldos impactan las cajas automáticamente — no hace falta cargarlos a mano.'],
    },
    {
      id: 'finanzas-cuentas-corrientes',
      titulo: 'Finanzas → Cuentas corrientes',
      grupo: 'Solo Administrativo y Admin',
      intro: 'El ranking de todos los odontólogos ordenados por deuda — la respuesta rápida a "¿quién me debe cuánto?".',
      queVes: ['Tabla con Odontólogo, comprobantes, hace cuánto no paga, severidad (color según cuán atrasado está) y deuda total.'],
      pasos: [
        'Para cobrar: en la fila, botón "Registrar pago" → Monto del pago * (se imputa a las deudas más viejas primero, puede ser un pago parcial), Medio (Transferencia o Efectivo), Fecha y Nota opcionales → "Registrar pago".',
        'Click en cualquier fila (fuera del botón) te lleva a la ficha 360 de ese odontólogo, con el detalle completo.',
      ],
      flujo: ['Esta vista es el ranking de TODOS los odontólogos por deuda; la ficha 360 de un odontólogo (dentro de Odontólogos) muestra el detalle de UNO solo.'],
    },
    {
      id: 'finanzas-triangulados',
      titulo: 'Finanzas → Triangulados',
      grupo: 'Solo Administrativo y Admin',
      intro: 'El listado de comprobantes recibidos que corresponden a sueldos y pagos a proveedores — directos o "triangulados" (por ejemplo, un odontólogo le paga directamente a un empleado en vez de pagarle al laboratorio).',
      queVes: ['Tabla con Fecha, Origen (Bot o Manual), Tipo de receptor, quién pagó, quién recibió, monto y comprobante adjunto si lo hay.'],
      flujo: [
        'Si la caja Compensación no queda en $0, en la pestaña "Resumen" de Finanzas aparece una alerta "Caja Compensación no cierra en cero" con cada triangulado incompleto.',
        'Para resolverlo: tocá el ítem de la alerta (te lleva al movimiento puntual en Cajas → Compensación) y completá o corregí el dato que falta hasta que la caja vuelva a $0.',
      ],
    },
    {
      id: 'sueldos',
      titulo: 'Finanzas → Sueldos',
      grupo: 'Solo Administrativo y Admin',
      intro: 'Los sueldos del personal, ligados a su usuario del sistema.',
      queVes: ['Total a pagar del período y cuántos integrantes tienen saldo pendiente.', 'Una card por integrante con Frecuencia, Monto base, lo que se le debe y su teléfono.'],
      pasos: [
        'Si un usuario recién activado en "Usuarios" no quedó dado de alta acá automáticamente, va a aparecer en el cuadro amarillo "Pendientes de alta en sueldos". Tocá "Dar de alta" en su fila → completá Nombre para el bot * (el bot lo compara contra el "Para" del comprobante de WhatsApp), Teléfono opcional, Frecuencia de pago y Monto base por ciclo * → "Dar de alta".',
        'Para pagar: en la card del integrante, "Registrar pago".',
        'Para cambiar el monto o la frecuencia: "Editar sueldo".',
        'Para ver todo lo pagado antes: "Ver histórico de pagos".',
        'Si necesitás pagarle a varios a la vez: botón "Distribuir cobro" arriba.',
      ],
      flujo: ['Si pagás de más en un ciclo, el excedente se descuenta automáticamente del próximo.'],
    },
    {
      id: 'proveedores',
      titulo: 'Proveedores',
      grupo: 'Solo Administrativo y Admin',
      intro: 'Tus proveedores de materiales y la deuda que el laboratorio tiene con cada uno.',
      queVes: ['Tabla con Proveedor, CUIT, Teléfono y Deuda pendiente. Click en una fila la despliega y muestra el detalle de sus deudas.'],
      pasos: [
        'Para cargar un proveedor: "+ Nuevo proveedor" → Nombre * es lo único obligatorio; CUIT, Email, Teléfono y Dirección son opcionales.',
        'Para registrar una deuda: expandí la fila del proveedor → "+ Nueva deuda" → Descripción * y Monto * son obligatorios; Vencimiento, Nº de factura y Observaciones son opcionales.',
        'Cuando la pagás: botón "Marcar pagada" en esa deuda.',
      ],
    },
    {
      id: 'bot',
      titulo: 'Bot de WhatsApp',
      grupo: 'Solo Administrativo y Admin',
      intro: 'El bot lee los comprobantes que llegan al grupo de WhatsApp y registra los pagos solo, con IA.',
      queVes: ['El badge de conexión (arriba) y la tabla de registros: Fecha, Estado, Tipo, Monto, quién pagó, quién recibió, N° de operación y el comprobante.', 'Filtros: Todos, Registrados, Rechazados, Duplicados.'],
      pasos: [
        'Para vincular el WhatsApp por primera vez (o si se desvinculó): tocá el badge de conexión → se abre el modal de estado con el código QR → 1) abrí WhatsApp en el celular del laboratorio, 2) andá a Configuración → Dispositivos vinculados, 3) tocá "Vincular un dispositivo" y escaneá el código. El QR se regenera solo; si expiró, usá "Regenerar QR".',
        '"🔄 Revisar mensajes perdidos" (solo si está conectado): hace que el bot repase el historial reciente del grupo por si quedó algún comprobante sin cargar durante una desconexión.',
        'Los pagos en efectivo que el bot detecta quedan como "pendientes de confirmación" en Finanzas → Sueldos: tenés que revisar el bloque correspondiente y tocar "✓ Confirmar" o "✗ Rechazar" (el rechazo te pide un motivo opcional).',
      ],
      flujo: ['El bot lee monto/operación/emisor/receptor del comprobante, evita duplicados, guarda el archivo y clasifica automáticamente: pago a empleado (sueldo), a proveedor o triangulado.'],
      captura: { id: 'cap-bot', descripcion: 'La pantalla de estado del bot (con el QR o el estado "conectado") y/o la lista de registros.' },
    },
    {
      id: 'reportes',
      titulo: 'Reportes',
      grupo: 'Solo Administrativo y Admin',
      intro: 'Los KPIs del laboratorio para ver cómo viene el negocio, con datos en tiempo real.',
      queVes: [
        '4 indicadores: Pedidos este mes, Facturación del mes, Deuda total de clientes y Entregas pendientes (todos comparados contra el período anterior).',
        'Gráficos: Pedidos por mes, Facturación mensual, Trabajos por tipo (donut) y Ranking de trabajos.',
        'Tabla "Morosos — top deudores" con los 5 que más deben y hace cuántos días.',
      ],
      queHaces: ['Exportás todo a PDF con el botón "Exportar PDF" arriba a la derecha.'],
      captura: { id: 'cap-reportes', descripcion: 'La pantalla de reportes con los indicadores.' },
    },
    {
      id: 'usuarios',
      titulo: 'Usuarios',
      grupo: 'Solo Administrativo y Admin',
      intro: 'Las cuentas del sistema — quién puede entrar y con qué rol.',
      queVes: ['Tabla con Usuario, Nombre, Rol (badge de color), Teléfono, Estado (Activo/Pendiente) y Acciones.'],
      pasos: [
        '"Nuevo usuario" → completá Nombre *, Apellido *, Nombre de usuario *, elegí el Rol * (Técnico de laboratorio, Administrativo, Odontólogo cliente o Administrador) y una Contraseña inicial * (mínimo 8 caracteres) → "Crear usuario".',
        'El usuario queda "Pendiente" hasta que alguien lo active: botón "Activar" en su fila. (Nota: si sos Admin, vos podés crearlo pero necesitás que un Administrativo lo active — es un control cruzado a propósito.)',
        'Para editar el teléfono (el que usa el bot para reconocer a la persona en los comprobantes): ícono de lápiz junto al teléfono → cargá el número (ej: 5491112345678) → "Guardar".',
        'Para dar de baja: botón "Desactivar" en su fila.',
      ],
      flujo: ['Al activar un usuario con rol Técnico o Administrativo, el sistema intenta darlo de alta automáticamente en Sueldos. Si no aparece ahí, revisá el cuadro "Pendientes de alta en sueldos" en Finanzas → Sueldos.'],
    },

    // ─────────────────── SOLO ADMIN ───────────────────
    {
      id: 'auditoria',
      titulo: 'Auditoría',
      grupo: 'Solo Admin',
      intro: 'La bitácora inmutable de todo lo que pasa en el sistema — nadie puede editarla ni borrarla, ni siquiera el Admin.',
      queVes: ['Cada evento con Hora, Tipo (Login, Pago, Estado, Editar, Crear, Eliminar, Backup), Usuario que lo hizo, Acción, Entidad afectada y el detalle.', 'Buscador por usuario, acción o entidad, y filtro por tipo de evento.'],
      queHaces: ['La usás para reconstruir "quién hizo qué y cuándo" ante cualquier duda — cambios de precio, pagos, bajas de usuarios, backups, todo queda registrado.'],
      captura: { id: 'cap-auditoria', descripcion: 'La tabla de auditoría con eventos de distintos tipos.' },
    },

    // ─────────────────── TU CUENTA ───────────────────
    {
      id: 'mi-perfil',
      titulo: 'Mi perfil',
      grupo: 'Tu cuenta',
      intro: 'Gestionás tu propia cuenta (distinto de Usuarios, que es donde un Admin/Administrativo gestiona a otros).',
      queVes: ['Tus datos y tu rol.'],
      queHaces: ['Editás tu nombre/apellido/teléfono y cambiás tu contraseña (pidiendo la actual).'],
      captura: { id: 'cap-mi-perfil', descripcion: 'La pantalla Mi perfil con las tarjetas de datos y cambio de contraseña.' },
    },
    {
      id: 'flujos',
      titulo: 'Flujos completos',
      grupo: 'Tu cuenta',
      intro: 'Cómo se conecta todo, siguiendo un caso real de principio a fin.',
      queHaces: [
        'Un trabajo de principio a fin: cargás el Pedido → en Producción pasa a EN PROCESO y se descuenta el Stock de la receta → pasa a LISTO → en Entregas confirmás con el monto y se genera la DEUDA → el odontólogo paga (total o parcial, cuando puede) y lo registrás en su Cuenta corriente → el saldo baja y entra a la caja correspondiente.',
        'Cobranza / "quién me debe": la deuda vive en la cuenta corriente de cada odontólogo; el ranking de morosos (Finanzas → Cuentas corrientes) te ordena quién debe más y hace cuántos días. Registrás cada pago manual (efectivo/transferencia) y todo queda al día.',
        'El bot y los comprobantes: el odontólogo (o el empleado) manda el comprobante al grupo de WhatsApp → el bot lo lee con IA, evita duplicados y lo clasifica (sueldo, proveedor o triangulado) → si es efectivo, alguien lo confirma o rechaza en Finanzas → Sueldos.',
        'Alta de un integrante nuevo: se crea el Usuario → un Administrativo lo activa → si no quedó de alta solo en Sueldos, se completa a mano desde el cuadro de pendientes.',
        'Stock: cada producción descuenta materiales según la receta del Catálogo; si algo perfora el mínimo, te llega la alerta por la campanita de notificaciones.',
      ],
    },
  ];
}
