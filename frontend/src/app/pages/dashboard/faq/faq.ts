import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface Pregunta {
  id: string;
  pregunta: string;
  respuesta: string;
}

interface Categoria {
  id: string;
  titulo: string;
  preguntas: Pregunta[];
}

@Component({
  selector: 'app-faq',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './faq.html',
  styleUrls: ['./faq.css'],
})
export class FaqComponent {
  busqueda = '';
  readonly abiertas = signal<Set<string>>(new Set());

  toggle(id: string): void {
    this.abiertas.update(s => {
      const nuevo = new Set(s);
      nuevo.has(id) ? nuevo.delete(id) : nuevo.add(id);
      return nuevo;
    });
  }

  estaAbierta(id: string): boolean {
    return this.abiertas().has(id);
  }

  /** Categorías visibles según la búsqueda: solo las que tienen al menos 1 pregunta que matchea. */
  categoriasFiltradas(): Categoria[] {
    const q = this.busqueda.trim().toLowerCase();
    if (!q) return this.categorias;
    return this.categorias
      .map(cat => ({
        ...cat,
        preguntas: cat.preguntas.filter(p =>
          p.pregunta.toLowerCase().includes(q) || p.respuesta.toLowerCase().includes(q))
      }))
      .filter(cat => cat.preguntas.length > 0);
  }

  readonly categorias: Categoria[] = [
    {
      id: 'acceso',
      titulo: 'Acceso y cuenta',
      preguntas: [
        {
          id: 'crear-usuario',
          pregunta: '¿Cómo doy de alta a un nuevo integrante del laboratorio?',
          respuesta: 'Solo un ADMIN puede hacerlo, desde Administración → Usuarios → "Nuevo usuario". El usuario queda pendiente de aprobación hasta que lo actives.',
        },
        {
          id: 'primer-login',
          pregunta: '¿Qué pasa la primera vez que un usuario nuevo inicia sesión?',
          respuesta: 'Antes de entrar al sistema, se le pide leer y aceptar los términos y condiciones. Es un paso único: no vuelve a aparecer en logins siguientes.',
        },
        {
          id: 'olvide-clave',
          pregunta: 'Me olvidé mi contraseña, ¿qué hago?',
          respuesta: 'Pedile a un ADMIN que te la restablezca desde Usuarios, o si ya estás logueado, cambiala vos mismo desde Mi perfil → Cambiar contraseña.',
        },
        {
          id: 'permisos-rol',
          pregunta: '¿Por qué no veo Finanzas, Proveedores o Reportes en el menú?',
          respuesta: 'Esas secciones son solo para ADMIN y ADMINISTRATIVO (Reportes es exclusivo de ADMIN). Si tu rol es Técnico u Odontólogo no las vas a ver, es esperado.',
        },
      ],
    },
    {
      id: 'pedidos',
      titulo: 'Pedidos y producción',
      preguntas: [
        {
          id: 'crear-pedido',
          pregunta: '¿Cómo cargo un pedido nuevo?',
          respuesta: 'Desde Pedidos → "Nuevo pedido": elegís odontólogo, paciente, tipo de trabajo (del Catálogo) y fecha de entrega. Si el tipo tiene receta cargada, el descuento de stock queda vinculado automáticamente.',
        },
        {
          id: 'pedido-atrasado',
          pregunta: '¿Cómo sé si un pedido está atrasado?',
          respuesta: 'Se resalta en rojo en la lista de Pedidos y en el panel de Inicio, comparando la fecha de entrega con la fecha actual.',
        },
        {
          id: 'mover-kanban',
          pregunta: '¿Cómo avanzo un trabajo de una etapa a otra en Producción?',
          respuesta: 'Arrastrás la tarjeta a la columna siguiente (Recibido → En proceso → Control → Listo). En el celular usás el botón "Avanzar" en vez de arrastrar.',
        },
        {
          id: 'stock-automatico',
          pregunta: '¿Por qué bajó el stock de un material solo?',
          respuesta: 'Cuando un pedido pasa a "En proceso", el sistema descuenta automáticamente los materiales según la receta cargada en el Catálogo para ese tipo de trabajo.',
        },
      ],
    },
    {
      id: 'entregas',
      titulo: 'Entregas y cobranza',
      preguntas: [
        {
          id: 'diferencia-entrega-cobro',
          pregunta: '¿Entregar un trabajo es lo mismo que cobrarlo?',
          respuesta: 'No. Al confirmar la entrega se genera la DEUDA del odontólogo por ese monto (queda en su cuenta corriente). El cobro es un paso aparte: se registra desde la ficha del odontólogo con "Registrar pago".',
        },
        {
          id: 'pago-parcial',
          pregunta: '¿Puedo registrar un pago parcial?',
          respuesta: 'Sí. El monto que registres se imputa primero a la deuda más antigua; si no la cubre entera, queda "Parcial" y el resto sigue pendiente.',
        },
        {
          id: 'ranking-morosos',
          pregunta: '¿Dónde veo quién me debe más?',
          respuesta: 'En Finanzas está el ranking de odontólogos morosos, ordenado por saldo y por antigüedad de la deuda.',
        },
      ],
    },
    {
      id: 'stock',
      titulo: 'Stock',
      preguntas: [
        {
          id: 'alerta-stock',
          pregunta: '¿Cómo me entero si un material está por agotarse?',
          respuesta: 'Cuando una salida de stock deja al material por debajo del mínimo configurado, te llega un aviso en la campanita de notificaciones del sistema (arriba a la derecha).',
        },
        {
          id: 'registrar-compra',
          pregunta: '¿Cómo cargo una compra de materiales?',
          respuesta: 'Desde Stock, registrás una entrada indicando el material y la cantidad comprada; se suma al stock actual.',
        },
      ],
    },
    {
      id: 'bot',
      titulo: 'Bot de WhatsApp',
      preguntas: [
        {
          id: 'que-hace-bot',
          pregunta: '¿Qué hace exactamente el bot de WhatsApp?',
          respuesta: 'Lee los comprobantes de pago que se mandan a los grupos monitoreados, extrae monto, número de operación, emisor y receptor con IA, evita duplicados y clasifica el pago (sueldo, proveedor o triangulado) para que lo confirmes en Bot WhatsApp.',
        },
        {
          id: 'bot-desconectado',
          pregunta: 'El bot estuvo desconectado y mandaron comprobantes mientras tanto, ¿se pierden?',
          respuesta: 'No. Al reconectarse, el bot revisa el historial reciente de los grupos y recupera lo que falte cargar, comparando contra lo que ya está en el sistema para no duplicar.',
        },
        {
          id: 'pie-antes-que-foto',
          pregunta: 'Mandé el emisor y receptor en un mensaje aparte del comprobante, ¿el bot lo toma igual?',
          respuesta: 'Sí, no importa el orden en que llegan: el bot empareja la imagen del comprobante con el texto de emisor/receptor apenas tiene los dos, sin importar cuál llegó primero.',
        },
        {
          id: 'reescanear-qr',
          pregunta: '¿Qué hago si el bot se desvincula de WhatsApp?',
          respuesta: 'Desde Bot WhatsApp tocás "Regenerar QR" y volvés a escanearlo con el WhatsApp del laboratorio, igual que la primera vez.',
        },
      ],
    },
  ];
}
