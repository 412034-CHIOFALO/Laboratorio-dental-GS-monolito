#!/usr/bin/env python3
"""Flujo principal de G&S de punta a punta, por la API de una app real corriendo.

Qué cubre (cada bloque es independiente: si uno falla, los demás siguen y se informa todo junto):
  1. sesión: login, cookie XSRF estable entre GETs de distintas cadenas de seguridad, logout, 401 después
     (regresión del 403 al cerrar sesión)
  2. pedido completo: odontólogo -> trabajo con receta -> pedido -> producción (descuenta stock) ->
     estados y transiciones inválidas -> entrega (nace la deuda) -> cobro parcial -> cobro total
  3. permisos por rol: el técnico ve pedidos pero no finanzas ni puede crear pedidos
  4. bot de WhatsApp: pago por transferencia y efectivo con anti-duplicados (mismo mensaje, mismo
     N° de operación, mismo archivo, consulta de mensajes conocidos, clave del bot inválida)

Uso (lo corre la CI después del smoke test; también sirve contra el servidor real o uno de ensayo):
    GS_ADMIN_PASSWORD=... GS_TECNICO_PASSWORD=... GS_BOT_API_KEY=... \\
        python3 e2e/flujo_principal.py --base http://localhost:8080

Deja datos con el prefijo "E2E" (odontólogo, trabajo del catálogo, pedido): no usarlo contra la base
real una vez que tenga datos de verdad, salvo que se quiera una prueba de humo (--limpiar los borra).
"""
import argparse
import base64
import importlib.util
import os
import sys
import time
import traceback

AQUI = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("demo", os.path.join(AQUI, "..", "demo", "cargar_datos_demo.py"))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)          # reutiliza el cliente HTTP (cookies, XSRF, multipart)
Api, ApiError, lista, fecha = demo.Api, demo.ApiError, demo.lista, demo.fecha

SUFIJO = str(int(time.time()))
RESULTADOS = []


def bloque(nombre):
    def deco(fn):
        def correr(*a, **k):
            try:
                fn(*a, **k)
                RESULTADOS.append((nombre, True, ""))
                print(f"  ✓ {nombre}", flush=True)
            except AssertionError as e:
                RESULTADOS.append((nombre, False, str(e)))
                print(f"  ✗ {nombre}: {e}", flush=True)
            except Exception as e:  # noqa: BLE001
                RESULTADOS.append((nombre, False, f"{type(e).__name__}: {e}"))
                print(f"  ✗ {nombre}: {type(e).__name__}: {e}", flush=True)
                traceback.print_exc()
        return correr
    return deco


def codigo(api, metodo, ruta, cuerpo=None, **kw):
    """Código HTTP de una llamada, sin lanzar excepción."""
    try:
        api.call(metodo, ruta, cuerpo, **kw)
        return 200
    except ApiError as e:
        return e.status


def login(base, usuario, clave):
    api = Api(base, "")
    api.call("POST", "/api/auth/login", {"username": usuario, "password": clave})
    return api


# ───────────────────────────── 1. sesión ─────────────────────────────
@bloque("sesión: la cookie XSRF no se pierde entre GETs y el logout funciona")
def sesion(base, clave):
    api = login(base, "admin", clave)
    assert "XSRF-TOKEN" in api.cookies, "el login no entregó la cookie XSRF-TOKEN"
    for ruta in ["/api/odontologos", "/api/pedidos", "/api/stock", "/api/catalogo", "/api/finanzas/proveedores",
                 "/api/auth/me", "/api/odontologos", "/api/stock"]:
        api.call("GET", ruta)
        assert "XSRF-TOKEN" in api.cookies, f"tras GET {ruta} el servidor borró la cookie XSRF-TOKEN"
    assert codigo(api, "POST", "/api/auth/logout") == 200, "logout con el header CSRF debería dar 200"
    assert codigo(api, "GET", "/api/auth/me") == 401, "después del logout /me debería dar 401"
    sin = login(base, "admin", clave)
    sin.cookies.pop("XSRF-TOKEN", None)
    assert codigo(sin, "POST", "/api/auth/logout") == 403, "sin el header CSRF el logout debería dar 403"


# ───────────────────────────── 2. pedido completo ─────────────────────────────
@bloque("pedido completo: producción descuenta stock, entrega genera deuda, cobro parcial y total")
def pedido_completo(base, clave, ctx):
    api = login(base, "admin", clave)
    ctx["api"] = api

    # material con stock para la receta (se crea uno propio: no depende de lo que haya en la base)
    mat = api.call("POST", "/api/stock", {
        "nombre": f"E2E material {SUFIJO}", "categoria": "OTRO", "stockActual": 10, "stockMinimo": 1,
        "unidadMedida": "u", "precioUnitario": 100, "descuentaStock": True})
    trabajo = api.call("POST", "/api/catalogo", {
        "nombre": f"E2E trabajo {SUFIJO}", "descripcion": "prueba", "precio": 100000, "categoria": "FIJA",
        "tiempoEstimadoDias": 5,
        "receta": [{"materialId": mat["id"], "materialNombre": mat["nombre"], "cantidad": 0.5, "unidad": "u"}]})
    odo = api.call("POST", "/api/odontologos", {"nombre": f"Dr. E2E {SUFIJO}", "email": f"e2e{SUFIJO}@demo.gs.test"})
    ctx.update(mat=mat, trabajo=trabajo, odo=odo)

    p = api.call("POST", "/api/pedidos", {
        "odontologoId": odo["id"], "odontologoNombre": odo["nombre"], "paciente": "Paciente E2E",
        "catalogoTrabajoId": trabajo["id"], "trabajo": trabajo["nombre"], "fechaEntrega": fecha(3),
        "prioridad": "NORMAL", "precioAcordado": 100000})
    assert p["estado"] == "RECIBIDO" and p.get("nroPedido"), f"pedido recién creado: {p}"
    ctx["pedido"] = p
    antes = api.call("GET", f"/api/stock/{mat['id']}")["stockActual"]

    api.call("PATCH", f"/api/pedidos/{p['id']}/estado", query={"nuevoEstado": "EN_PROCESO"})
    despues = api.call("GET", f"/api/stock/{mat['id']}")["stockActual"]
    assert abs((antes - despues) - 0.5) < 1e-6, f"al entrar en producción debía descontar 0,5 del stock: {antes} -> {despues}"
    api.call("PATCH", f"/api/pedidos/{p['id']}/estado", query={"nuevoEstado": "EN_PROCESO"})   # idempotente
    assert api.call("GET", f"/api/stock/{mat['id']}")["stockActual"] == despues, "repetir el estado no debe descontar de nuevo"

    assert codigo(api, "PATCH", f"/api/pedidos/{p['id']}/estado", query={"nuevoEstado": "ENTREGADO"}) in (400, 409, 422), \
        "pasar a ENTREGADO por el kanban debe rechazarse (la entrega genera la deuda)"
    api.call("PATCH", f"/api/pedidos/{p['id']}/estado", query={"nuevoEstado": "CONTROL"})
    api.call("PATCH", f"/api/pedidos/{p['id']}/estado", query={"nuevoEstado": "LISTO"})
    assert api.call("GET", f"/api/pedidos/{p['id']}")["estado"] == "LISTO"

    api.call("PATCH", f"/api/pedidos/{p['id']}/entregar", {"retiradoPor": "Cadete E2E", "monto": 100000})
    assert api.call("GET", f"/api/pedidos/{p['id']}")["estado"] == "ENTREGADO"
    comp = [c for c in lista(api.call("GET", "/api/finanzas/comprobantes")) if c["pedidoId"] == p["id"]]
    assert len(comp) == 1, f"al entregar debía nacer exactamente un comprobante, hay {len(comp)}"
    assert float(comp[0]["monto"]) == 100000 and comp[0]["estadoPago"] == "PENDIENTE", comp[0]
    ctx["comprobante_id"] = comp[0]["id"]

    api.call("POST", f"/api/finanzas/odontologos/{odo['id']}/pagos", {"monto": 40000, "medio": "EFECTIVO", "fecha": fecha(0), "nota": "E2E"})
    c = api.call("GET", f"/api/finanzas/comprobantes/{comp[0]['id']}")
    assert c["estadoPago"] == "PARCIAL" and float(c["montoPagado"]) == 40000, c
    api.call("POST", f"/api/finanzas/odontologos/{odo['id']}/pagos", {"monto": 60000, "medio": "TRANSFERENCIA", "fecha": fecha(0)})
    c = api.call("GET", f"/api/finanzas/comprobantes/{comp[0]['id']}")
    assert c["estadoPago"] == "COBRADO" and float(c["montoPagado"]) == 100000, c


# ───────────────────────────── 3. permisos por rol ─────────────────────────────
@bloque("permisos: el técnico ve pedidos pero no finanzas, no crea pedidos ni lista usuarios")
def permisos(base, clave_tecnico):
    t = login(base, "tecnico1", clave_tecnico)
    assert codigo(t, "GET", "/api/pedidos") == 200
    assert codigo(t, "GET", "/api/finanzas/comprobantes") == 403
    assert codigo(t, "GET", "/api/finanzas/cajas/resumen") == 403
    assert codigo(t, "GET", "/api/auth/usuarios") == 403
    assert codigo(t, "POST", "/api/pedidos", {}) == 403
    assert codigo(t, "POST", "/api/odontologos", {}) == 403
    assert codigo(Api(base, ""), "GET", "/api/pedidos") in (401, 403), "sin sesión no se debe poder leer pedidos"


# ───────────────────────────── 4. bot ─────────────────────────────
@bloque("bot: transferencia sin duplicados (mismo mensaje, misma operación, mismo archivo)")
def bot_transferencia(base, clave_bot, ctx):
    api, odo = ctx["api"], ctx["odo"]
    # el empleado receptor: se da de alta a tecnico1 en sueldos si todavía no está
    u = [x for x in lista(api.call("GET", "/api/auth/usuarios")) if x["username"] == "tecnico1"][0]
    nombre = f"{u['nombre']} {u['apellido']}".strip()
    try:
        api.call("POST", "/api/finanzas/sueldos/empleados", {"usuarioId": u["id"], "nombre": nombre, "rol": "TECNICO",
                                                              "telefono": "", "frecuencia": "SEMANAL", "montoBase": 100000})
    except ApiError as e:
        assert e.status == 409, f"alta de empleado: {e}"
    api.call("POST", "/api/finanzas/cajas/movimiento", {"tipo": "INGRESO", "tipoCaja": "FISICA", "concepto": "E2E fondo",
                                                        "monto": 1000000, "fechaMovimiento": fecha(0), "creadoPor": "e2e"})
    ctx["empleado"] = nombre

    bot = Api(base, "")
    h = {"X-Bot-Api-Key": clave_bot}
    archivo = base64.b64encode(f"%PDF-E2E {SUFIJO}".encode()).decode()
    base_req = {"receptorNombre": nombre, "emisor": odo["nombre"], "monto": 15000, "fecha": fecha(0),
                "comprobanteBase64": archivo, "comprobanteMime": "application/pdf", "comprobanteNombre": "e2e.pdf"}

    r1 = bot.call("POST", "/api/finanzas/sueldos/pago-automatico", dict(base_req, idOperacion=f"E2E-OP-{SUFIJO}",
                                                                        idMensaje=f"E2E-MSG-{SUFIJO}-1"), headers=h)
    assert r1["estado"] == "REGISTRADO" and not r1.get("repetido"), f"primer envío: {r1}"

    r2 = bot.call("POST", "/api/finanzas/sueldos/pago-automatico", dict(base_req, idOperacion=f"E2E-OP-{SUFIJO}",
                                                                        idMensaje=f"E2E-MSG-{SUFIJO}-1"), headers=h)
    assert r2.get("repetido") is True and r2["id"] == r1["id"], f"mismo mensaje debía devolver el mismo registro: {r2}"

    r3 = bot.call("POST", "/api/finanzas/sueldos/pago-automatico", dict(base_req, idOperacion=f"E2E-OP-{SUFIJO}",
                                                                        idMensaje=f"E2E-MSG-{SUFIJO}-2"), headers=h)
    assert r3["estado"] == "DUPLICADO", f"mismo N° de operación en otro mensaje: {r3}"

    r4 = bot.call("POST", "/api/finanzas/sueldos/pago-automatico", dict(base_req, idMensaje=f"E2E-MSG-{SUFIJO}-3"), headers=h)
    assert r4["estado"] == "DUPLICADO" and "archivo" in (r4.get("mensaje") or ""), \
        f"mismo archivo sin N° de operación debía ser DUPLICADO: {r4}"

    conocidos = bot.call("POST", "/api/finanzas/sueldos/registros-bot/conocidos",
                         {"ids": [f"E2E-MSG-{SUFIJO}-1", "no-existe"]}, headers=h)
    assert conocidos == [f"E2E-MSG-{SUFIJO}-1"], f"mensajes conocidos: {conocidos}"

    assert codigo(bot, "POST", "/api/finanzas/sueldos/pago-automatico", dict(base_req), headers={"X-Bot-Api-Key": "clave-incorrecta"}) in (401, 403)
    assert codigo(bot, "POST", "/api/finanzas/sueldos/registros-bot/conocidos", {"ids": []}) in (401, 403), "sin clave del bot"


@bloque("bot: efectivo con anti-duplicados y confirmación desde el panel")
def bot_efectivo(base, clave_bot, ctx):
    api, nombre, odo = ctx["api"], ctx["empleado"], ctx["odo"]
    bot = Api(base, "")
    h = {"X-Bot-Api-Key": clave_bot}
    req = {"receptorNombre": nombre, "monto": 5000, "emisor": odo["nombre"], "idMensaje": f"E2E-MSG-{SUFIJO}-EF"}
    e1 = bot.call("POST", "/api/finanzas/sueldos/pago-efectivo", req, headers=h)
    assert e1["estado"] == "PENDIENTE" and not e1.get("repetido"), e1
    e2 = bot.call("POST", "/api/finanzas/sueldos/pago-efectivo", req, headers=h)
    assert e2.get("repetido") is True and e2["id"] == e1["id"], f"el mismo mensaje de efectivo no debe crear otro borrador: {e2}"
    pendientes = [x for x in lista(api.call("GET", "/api/finanzas/sueldos/pendientes-efectivo")) if x["id"] == e1["id"]]
    assert len(pendientes) == 1, "debe haber exactamente un borrador pendiente"
    ok = api.call("POST", f"/api/finanzas/sueldos/registros-bot/{e1['id']}/confirmar")
    assert ok["estado"] == "REGISTRADO", ok


def limpiar(ctx):
    api = ctx.get("api")
    if not api:
        return
    if ctx.get("pedido"):
        print("  (el pedido entregado y su cobro quedan: no se pueden borrar a propósito)")
    if ctx.get("trabajo"):
        codigo(api, "DELETE", f"/api/catalogo/{ctx['trabajo']['id']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--limpiar", action="store_true", help="borra el trabajo de catálogo que crea la prueba")
    args = ap.parse_args()
    clave, clave_t, clave_bot = (os.environ.get(k, "") for k in ("GS_ADMIN_PASSWORD", "GS_TECNICO_PASSWORD", "GS_BOT_API_KEY"))
    if not (clave and clave_t and clave_bot):
        sys.exit("Faltan GS_ADMIN_PASSWORD, GS_TECNICO_PASSWORD o GS_BOT_API_KEY en el entorno.")

    print(f"E2E contra {args.base} (sufijo {SUFIJO})")
    ctx = {}
    sesion(args.base, clave)
    pedido_completo(args.base, clave, ctx)
    permisos(args.base, clave_t)
    if "odo" in ctx:
        bot_transferencia(args.base, clave_bot, ctx)
        if "empleado" in ctx:
            bot_efectivo(args.base, clave_bot, ctx)
    else:
        print("  (se saltean los bloques del bot: el pedido completo no llegó a crear el odontólogo)")
    if args.limpiar:
        limpiar(ctx)

    fallidos = [r for r in RESULTADOS if not r[1]]
    print(f"\n{len(RESULTADOS) - len(fallidos)}/{len(RESULTADOS)} bloques OK")
    for nombre, _, motivo in fallidos:
        print(f"  FALLÓ: {nombre}\n         {motivo}")
    return 1 if fallidos else 0


if __name__ == "__main__":
    sys.exit(main())
