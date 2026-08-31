/**
 * Build de demo pública (GitHub Pages) — 100% mocks, sin backend real.
 * GH Pages solo sirve estático: nunca hay un gateway al que pegarle, así que
 * estas URLs no se usan (useMocks intercepta todo antes de tocar HttpClient).
 */
export const environment = {
  production: true,
  useMocks: true,
  gatewayUrl: '',
  apiUrl: '',
  pedidosUrl: '',
  finanzasUrl: '',
  stockUrl: '',
  loginUrl: '/api/auth/login',
};
