/** Lo que el formulario «Pedido a crear» tiene cargado, tal como está en pantalla. */
export interface DatosPedidoSolicitud {
  odontologoId: number | null;
  odontologoNombre: string;
  paciente: string;
  trabajo: string;
  /** true si en el selector de trabajo se eligió «Otro trabajo» (el nombre se escribe a mano). */
  eligioOtroTrabajo: boolean;
  /** AAAA-MM-DD, como lo da un campo de fecha; vacío si no se eligió. */
  fechaEntrega: string;
  precio: number | null;
}

/** Un dato que impide crear el pedido: qué es, dónde está en la pantalla y cómo se le explica a la persona. */
export interface Faltante {
  /** Id del campo del formulario, para llevar el foco ahí. */
  campoId: string;
  /** Para la lista de arriba del botón: «el paciente». */
  resumen: string;
  /** Para debajo del campo: una oración completa que dice qué hacer. */
  mensaje: string;
}

/**
 * Lo que falta (o está mal) para poder crear el pedido, en el orden en que aparece el formulario. Son las mismas reglas
 * que valida el servidor (odontólogo, paciente, trabajo y fecha futura obligatorios, precio no negativo): así la persona
 * se entera acá, con una explicación, y no cuando el botón simplemente no responde.
 *
 * El precio NO es obligatorio: sin precio el pedido se crea igual y queda marcado «sin precio».
 */
export function faltantesDelPedido(d: DatosPedidoSolicitud, minimaEntrega: string): Faltante[] {
  const faltan: Faltante[] = [];

  if (d.odontologoId == null && d.odontologoNombre.trim().length === 0) {
    faltan.push({
      campoId: 'odontologo', resumen: 'el odontólogo',
      mensaje: 'Elegí un odontólogo de la lista o escribí su nombre.',
    });
  }

  if (d.paciente.trim().length === 0) {
    faltan.push({
      campoId: 'paciente', resumen: 'el paciente',
      mensaje: 'Escribí el nombre del paciente: todo pedido lo necesita.',
    });
  }

  if (d.trabajo.trim().length === 0) {
    faltan.push(d.eligioOtroTrabajo
      ? { campoId: 'trabajo', resumen: 'qué trabajo es', mensaje: 'Escribí qué trabajo es.' }
      : { campoId: 'trabajo-cat', resumen: 'el trabajo', mensaje: 'Elegí un trabajo del catálogo o «Otro trabajo» para escribirlo.' });
  }

  if (!d.fechaEntrega) {
    faltan.push({ campoId: 'entrega', resumen: 'la fecha de entrega', mensaje: 'Elegí la fecha de entrega.' });
  } else if (d.fechaEntrega < minimaEntrega) {
    faltan.push({
      campoId: 'entrega', resumen: 'una fecha de entrega de mañana en adelante',
      mensaje: 'La fecha de entrega tiene que ser de mañana en adelante.',
    });
  }

  if (d.precio != null && d.precio < 0) {
    faltan.push({ campoId: 'precio', resumen: 'un precio que no sea negativo', mensaje: 'El precio no puede ser negativo.' });
  }

  return faltan;
}

/** «el paciente», «el paciente y la fecha de entrega», «el odontólogo, el paciente y el trabajo». */
export function enumerar(items: string[]): string {
  if (items.length <= 1) return items[0] ?? '';
  return `${items.slice(0, -1).join(', ')} y ${items[items.length - 1]}`;
}
