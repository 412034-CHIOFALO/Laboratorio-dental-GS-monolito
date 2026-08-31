import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';

@Injectable({ providedIn: 'root' })
export class TutorialService {

  private readonly router = inject(Router);

  async iniciarTour(): Promise<void> {
    const { driver } = await import('driver.js');

    // Cada paso resalta el item del sidebar, ENTRA a la seccion (navega) y
    // explica para que sirve. El popover sale a la derecha del item del sidebar.
    const paso = (tourId: string, route: string, title: string, description: string) => ({
      element: `[data-tour="${tourId}"]`,
      popover: { title, description, side: 'right' as const, align: 'start' as const },
      onHighlightStarted: () => { this.router.navigateByUrl(route); },
    });

    const driverObj = driver({
      showProgress: true,
      animate: true,
      smoothScroll: true,
      nextBtnText: 'Siguiente →',
      prevBtnText: '← Anterior',
      doneBtnText: '¡Listo!',
      progressText: 'Paso {{current}} de {{total}}',
      popoverClass: 'gs-tour-popover',
      steps: [
        {
          popover: {
            title: 'Bienvenido al sistema del Laboratorio GS',
            description: 'Te muestro <b>para qué sirve cada sección</b> del menú. Voy resaltando cada una y entrando a ella mientras te explico — solo tocá «Siguiente».',
            align: 'center',
          },
        },
        paso('nav-inicio', '/dashboard', 'Inicio',
          'Tu <b>panel del día</b>: un resumen del estado del laboratorio (pedidos en curso, atrasados, alertas) y acceso rápido a todo. Es la pantalla a la que volvés siempre.'),
        paso('nav-pedidos', '/dashboard/pedidos', 'Pedidos',
          'El corazón del sistema. Acá <b>cargás cada trabajo</b> que pide un odontólogo (paciente, tipo, fecha de entrega) y seguís su estado. <b>Sirve para</b> no perder ningún pedido ni fecha.'),
        paso('nav-produccion', '/dashboard/produccion', 'Producción',
          'El <b>tablero Kanban</b> del taller. Movés cada trabajo por sus etapas: Recibido → En proceso → Control → Listo. <b>Sirve para</b> que el técnico vea de un vistazo qué hacer y en qué orden.'),
        paso('nav-entregas', '/dashboard/entregas', 'Entregas',
          'Cuando un trabajo está listo, lo <b>confirmás como entregado</b> y se arma el mensaje de WhatsApp para el cadete/odontólogo. <b>Sirve para</b> cerrar el círculo con trazabilidad.'),
        paso('nav-catalogo', '/dashboard/catalogo', 'Catálogo',
          'Tus <b>tipos de trabajo</b> (Hawley, placas, expansores…) con precio y <b>receta de materiales</b>. <b>Sirve para</b> cotizar rápido y que el stock se descuente solo al producir.'),
        paso('nav-stock', '/dashboard/stock', 'Stock',
          'El <b>inventario de materiales</b>. Cuando algo cae por debajo del mínimo, el sistema te <b>avisa por WhatsApp</b>. <b>Sirve para</b> no quedarte sin material en medio de un trabajo.'),
        paso('nav-finanzas', '/dashboard/finanzas', 'Finanzas',
          'Las <b>3 cajas</b>, los <b>sueldos</b> y los <b>cobros</b> del laboratorio. El bot registra los pagos automáticamente acá. <b>Sirve para</b> tener todo el control financiero en un solo lugar.'),
        paso('nav-bot', '/dashboard/bot-registros', 'Bot de WhatsApp',
          'El bot <b>lee los comprobantes</b> del grupo y registra los pagos solo (con IA). Acá <b>confirmás o rechazás</b> lo detectado. <b>Sirve para</b> no cargar los pagos a mano.'),
        paso('nav-reportes', '/dashboard/reportes', 'Reportes',
          'Resúmenes y <b>KPIs</b> del laboratorio: facturación, cobranzas, cajas. <b>Sirve para</b> ver cómo viene el negocio y exportar un cierre mensual.'),
        {
          element: '[data-tour="nav-manual"]',
          popover: {
            title: '¡Eso es todo!',
            description: 'Ya conocés las secciones principales. Para el detalle paso a paso de cada una, entrá al <b>Manual</b>. ¡A trabajar!',
            side: 'right',
            align: 'start',
          },
          onHighlightStarted: () => { this.router.navigateByUrl('/dashboard/manual'); },
        },
      ],
    });

    driverObj.drive();
  }
}
