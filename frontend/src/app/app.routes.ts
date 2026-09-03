import { Routes } from '@angular/router';
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
import { ProveedoresComponent } from './pages/dashboard/proveedores/proveedores';
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
      { path: '',            component: DashboardHomeComponent },
      { path: 'pedidos',     component: PedidosComponent },
      { path: 'odontologos', component: OdontologosComponent },
      { path: 'odontologos/:id/historial', component: OdontologoHistorialComponent },
      { path: 'produccion',  component: ProduccionComponent },
      { path: 'entregas',    component: EntregasComponent },
      { path: 'catalogo',    component: CatalogoComponent },
      { path: 'stock',       component: StockComponent },
      { path: 'finanzas',    component: FinanzasComponent },
      { path: 'proveedores', component: ProveedoresComponent },
      { path: 'bot-registros', component: BotRegistrosComponent },
      { path: 'reportes',    component: ReportesComponent },
      { path: 'documentos',  component: DocumentosComponent },
      { path: 'escaneos',    component: EscaneosComponent },
      { path: 'auditoria',      component: AuditoriaComponent },
      { path: 'usuarios',       component: UsuariosComponent },
      { path: 'mi-perfil',      component: MiPerfilComponent },
      { path: 'configuracion',  component: ConfiguracionComponent },
      { path: 'manual',         component: ManualComponent },
      { path: 'faq',            component: FaqComponent },
    ]
  },
  // Páginas de error
  { path: 'sin-permisos', component: ErrorPageComponent, data: { tipo: 'forbidden' } },
  { path: 'error',        component: ErrorPageComponent, data: { tipo: 'server' } },
  { path: '**',           component: ErrorPageComponent, data: { tipo: 'not-found' } },
];
