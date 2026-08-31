/**
 * Producción — el frontend se sirve desde nginx que hace proxy de /api/*
 * hacia el api-gateway interno del container (incluido /api/auth/* para login).
 * Por eso todas las URLs son relativas: no hay CORS porque comparten el origen.
 */
export const environment = {
  production: true,
  useMocks: false,

  // URLs relativas — nginx hace proxy al api-gateway (mismo origen, sin CORS).
  gatewayUrl: '',
  apiUrl:     '',
  pedidosUrl: '',
  finanzasUrl:'',
  stockUrl:   '',

  // Login vía gateway (nginx proxy /api/ → gateway). NUNCA usar localhost:8081 acá.
  loginUrl: '/api/auth/login',
};
