import { Router, Routes } from '@angular/router';
import { inject } from '@angular/core';
import { LandingPage } from './pages/landing-page/landing-page';
import { LoginComponent } from './pages/login/login';
import { DashboardComponent } from './pages/dashboard/dashboard';
import { DashboardHomeComponent } from './pages/dashboard/home/dashboard-home';
import { UsuariosComponent } from './pages/dashboard/usuarios/usuarios';
import { PedidosComponent } from './pages/dashboard/pedidos/pedidos';
import { ProduccionComponent } from './pages/dashboard/produccion/produccion';
import { CatalogoComponent } from './pages/dashboard/catalogo/catalogo';
import { FinanzasComponent } from './pages/dashboard/finanzas/finanzas';
import { StockComponent } from './pages/dashboard/stock/stock';
import { EntregasComponent } from './pages/dashboard/entregas/entregas';
import { ReportesComponent } from './pages/dashboard/reportes/reportes';
import { DocumentosComponent } from './pages/dashboard/documentos/documentos';
import { EscaneosComponent } from './pages/dashboard/escaneos/escaneos';
import { AuditoriaComponent } from './pages/dashboard/auditoria/auditoria';
import { OdontologosComponent } from './pages/dashboard/odontologos/odontologos';
import { OdontologoHistorialComponent } from './pages/dashboard/odontologos/historial/odontologo-historial';
import { BotRegistrosComponent } from './pages/dashboard/bot-registros/bot-registros';
import { SolicitudesComponent } from './pages/dashboard/solicitudes/solicitudes';
import { ManualComponent } from './pages/dashboard/manual/manual';
import { FaqComponent } from './pages/dashboard/faq/faq';
import { MiPerfilComponent } from './pages/dashboard/mi-perfil/mi-perfil';
import { ConfiguracionComponent } from './pages/dashboard/configuracion/configuracion';
import { CatalogoPublicoComponent } from './pages/catalogo-publico/catalogo-publico';
import { ErrorPageComponent } from './pages/error/error-page';
import { TerminosComponent } from './pages/terminos/terminos';
import { authGuard, termsGuard, forcePasswordChangeGuard } from './guards/auth.guard';
import { catalogoPublicoGuard } from './guards/catalogo-publico.guard';

export const routes: Routes = [
  { path: '', component: LandingPage },
  { path: 'login', component: LoginComponent },
  { path: 'terminos', component: TerminosComponent, canActivate: [authGuard] },
  { path: 'catalogo', component: CatalogoPublicoComponent, canActivate: [catalogoPublicoGuard] },
  { path: 'catalogo-no-disponible', component: ErrorPageComponent, data: { tipo: 'catalogo-deshabilitado' } },
  {
    path: 'dashboard',
    component: DashboardComponent,
    canActivate: [termsGuard],
    canActivateChild: [forcePasswordChangeGuard],
    children: [
      { path: '',            loadComponent: () => import('./pages/dashboard/home/dashboard-home').then(m => m.DashboardHomeComponent) },
      { path: 'pedidos',     loadComponent: () => import('./pages/dashboard/pedidos/pedidos').then(m => m.PedidosComponent) },
      { path: 'odontologos', loadComponent: () => import('./pages/dashboard/odontologos/odontologos').then(m => m.OdontologosComponent) },
      { path: 'odontologos/:id/historial', loadComponent: () => import('./pages/dashboard/odontologos/historial/odontologo-historial').then(m => m.OdontologoHistorialComponent) },
      { path: 'produccion',  loadComponent: () => import('./pages/dashboard/produccion/produccion').then(m => m.ProduccionComponent) },
      { path: 'entregas',    loadComponent: () => import('./pages/dashboard/entregas/entregas').then(m => m.EntregasComponent) },
      { path: 'catalogo',    loadComponent: () => import('./pages/dashboard/catalogo/catalogo').then(m => m.CatalogoComponent) },
      { path: 'stock',       loadComponent: () => import('./pages/dashboard/stock/stock').then(m => m.StockComponent) },
      { path: 'finanzas',    loadComponent: () => import('./pages/dashboard/finanzas/finanzas').then(m => m.FinanzasComponent) },
      // Proveedores es una sección de Finanzas; el link viejo sigue funcionando.
      { path: 'proveedores', pathMatch: 'full', redirectTo: () => inject(Router).parseUrl('/dashboard/finanzas?seccion=proveedores') },
      { path: 'bot-registros', loadComponent: () => import('./pages/dashboard/bot-registros/bot-registros').then(m => m.BotRegistrosComponent) },
      { path: 'solicitudes', loadComponent: () => import('./pages/dashboard/solicitudes/solicitudes').then(m => m.SolicitudesComponent) },
      { path: 'reportes',    loadComponent: () => import('./pages/dashboard/reportes/reportes').then(m => m.ReportesComponent) },
      { path: 'documentos',  loadComponent: () => import('./pages/dashboard/documentos/documentos').then(m => m.DocumentosComponent) },
      { path: 'escaneos',    loadComponent: () => import('./pages/dashboard/escaneos/escaneos').then(m => m.EscaneosComponent) },
      { path: 'auditoria',      loadComponent: () => import('./pages/dashboard/auditoria/auditoria').then(m => m.AuditoriaComponent) },
      { path: 'usuarios',       loadComponent: () => import('./pages/dashboard/usuarios/usuarios').then(m => m.UsuariosComponent) },
      { path: 'mi-perfil',      loadComponent: () => import('./pages/dashboard/mi-perfil/mi-perfil').then(m => m.MiPerfilComponent) },
      { path: 'configuracion',  loadComponent: () => import('./pages/dashboard/configuracion/configuracion').then(m => m.ConfiguracionComponent) },
      { path: 'manual',         loadComponent: () => import('./pages/dashboard/manual/manual').then(m => m.ManualComponent) },
      { path: 'faq',            loadComponent: () => import('./pages/dashboard/faq/faq').then(m => m.FaqComponent) },
    ]
  },
  // Páginas de error
  { path: 'sin-permisos', component: ErrorPageComponent, data: { tipo: 'forbidden' } },
  { path: 'error',        component: ErrorPageComponent, data: { tipo: 'server' } },
  { path: '**',           component: ErrorPageComponent, data: { tipo: 'not-found' } },
];
