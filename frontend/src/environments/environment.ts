export const environment = {
  production: false,

  // ─── MODO ────────────────────────────────────────────────────────────────────
  // true  → mocks en memoria (demo sin backend, login: admin/admin123)
  // false → backend real a través del api-gateway (requiere todos los ms corriendo)
  useMocks: true,

  // ─── GATEWAY ─────────────────────────────────────────────────────────────────
  // Todos los microservicios se acceden a través del gateway en puerto 8080.
  // Para usar el backend real: cambiar useMocks a false y levantar los servicios.
  gatewayUrl: 'http://localhost:8080',

  // Aliases por ms — apuntan todos al gateway (el gateway rutea internamente)
  apiUrl:      'http://localhost:8080',  // → ms-catalogo  (/api/catalogo/**)
  pedidosUrl:  'http://localhost:8080',  // → ms-pedidos    (/api/pedidos/**)
  finanzasUrl: 'http://localhost:8080',  // → ms-finanzas   (/api/finanzas/**)
  stockUrl:    'http://localhost:8080',  // → ms-stock      (/api/stock/**)

  // URL del login — va por el gateway, igual que el resto de los endpoints.
  // El gateway whitelistea POST /api/auth/login como público (sin JWT).
  loginUrl: 'http://localhost:8080/api/auth/login',
};
