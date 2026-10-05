#!/usr/bin/env python3
"""Carga datos de muestra de G&S por la API REAL, para probar el flujo de punta a punta.

Por qué por la API y no por SQL: así corren las reglas de negocio de verdad (el
stock se descuenta al entrar en producción, la deuda del odontólogo nace al
entregar, la caja se mueve con cada pago) y los datos quedan consistentes.

Qué carga: usuarios (técnicos y administrativa), recetas del catálogo, stock con
alertas, proveedores y deudas, 10 odontólogos, 34 pedidos en todos los estados
(con documentos y escaneos 3D), cuentas corrientes con distinta mora, caja,
sueldos, pagos que "llegan por el bot de WhatsApp" y reportes mensuales. Las
fechas se reparten en los últimos ~45 días con un ajuste por SQL (la API no deja
poner fechas pasadas en los pedidos).

NO hace falta WhatsApp: los pagos del bot se simulan llamando al mismo endpoint
que usa el bot. Los odontólogos de muestra NO tienen teléfono a propósito, para
que nunca se mande un WhatsApp a un número real al marcar un pedido como LISTO.

Uso (en el servidor; lo normal es ./demo/cargar-demo.sh, que arma el entorno):
    GS_ADMIN_PASSWORD=... GS_BOT_API_KEY=... DB_ROOT_PASSWORD=... \\
    python3 demo/cargar_datos_demo.py --base https://127.0.0.1 --host tu-dominio \\
        --mysql-container gs-monolito-mysql-1

Las contraseñas de los usuarios de muestra se generan al azar y quedan en un
archivo (chmod 600), nunca en el repo. Corre una sola vez: si ya hay odontólogos
se frena, salvo --forzar.
"""
import argparse
import base64
import json
import math
import os
import secrets
import ssl
import struct
import subprocess
import sys
import uuid
import zlib
from datetime import date, timedelta
from urllib import error, parse, request

HOY = date.today()


def fecha(delta_dias):
    return (HOY + timedelta(days=delta_dias)).isoformat()


# ───────────────────────────── cliente HTTP ─────────────────────────────
class ApiError(Exception):
    def __init__(self, metodo, ruta, status, cuerpo):
        super().__init__(f"{metodo} {ruta} -> {status}: {cuerpo}")
        self.status = status


class Api:
    """Cliente mínimo: guarda las cookies a mano y manda el X-XSRF-TOKEN (CSRF)."""

    def __init__(self, base, host):
        self.base, self.host, self.cookies = base.rstrip("/"), host, {}
        self.ctx = ssl.create_default_context()
        self.ctx.check_hostname = False          # se llega por loopback/IP, no por el dominio
        self.ctx.verify_mode = ssl.CERT_NONE

    def _guardar_cookies(self, resp):
        for c in resp.headers.get_all("Set-Cookie") or []:
            nombre, _, valor = c.split(";")[0].partition("=")
            if valor == "" or "Max-Age=0" in c:
                self.cookies.pop(nombre.strip(), None)
            else:
                self.cookies[nombre.strip()] = valor

    def call(self, metodo, ruta, cuerpo=None, query=None, datos=None, ctype=None, headers=None):
        url = self.base + ruta + ("?" + parse.urlencode(query) if query else "")
        h = {"Accept": "application/json"}
        if self.host:
            h["Host"] = self.host
        raw = None
        if cuerpo is not None:
            raw, h["Content-Type"] = json.dumps(cuerpo).encode(), "application/json"
        if datos is not None:
            raw, h["Content-Type"] = datos, ctype
        if self.cookies:
            h["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        if metodo != "GET" and "XSRF-TOKEN" in self.cookies:
            h["X-XSRF-TOKEN"] = self.cookies["XSRF-TOKEN"]
        h.update(headers or {})
        try:
            with request.urlopen(request.Request(url, data=raw, method=metodo, headers=h),
                                 context=self.ctx, timeout=90) as resp:
                self._guardar_cookies(resp)
                salida = resp.read()
        except error.HTTPError as e:
            raise ApiError(metodo, ruta, e.code, e.read().decode("utf-8", "replace")[:300])
        if not salida:
            return None
        try:
            return json.loads(salida)
        except ValueError:
            return salida


def lista(r):
    """Algunas listas vienen paginadas ({content: [...]}), otras planas."""
    if isinstance(r, dict):
        for k in ("content", "items", "data"):
            if k in r:
                return r[k]
    return r or []


# ───────────────────────────── utilidades de reporte ─────────────────────────────
FALLAS = []


def paso(titulo):
    print(f"\n== {titulo}", flush=True)


def intentar(descripcion, fn):
    """Corre un paso; si falla lo anota y sigue (un dato de muestra no debe frenar todo)."""
    try:
        return fn()
    except ApiError as e:
        FALLAS.append(f"{descripcion}: {e}")
        print(f"   ✗ {descripcion}: {e}", flush=True)
    except Exception as e:  # noqa: BLE001 - queremos seguir con el resto
        FALLAS.append(f"{descripcion}: {type(e).__name__}: {e}")
        print(f"   ✗ {descripcion}: {type(e).__name__}: {e}", flush=True)
    return None


def sql(args, consulta):
    if not args.mysql_container:
        print("   (sin --mysql-container: me salteo el ajuste de fechas por SQL)")
        return False
    env = dict(os.environ, MYSQL_PWD=os.environ.get("DB_ROOT_PASSWORD", ""))
    r = subprocess.run(["docker", "exec", "-e", "MYSQL_PWD", args.mysql_container,
                        "mysql", "-uroot", "gs_auth", "-e", consulta],
                       env=env, capture_output=True, text=True)
    if r.returncode != 0:
        FALLAS.append(f"SQL: {r.stderr.strip()[:200]}")
        print(f"   ✗ SQL: {r.stderr.strip()[:200]}")
        return False
    return True


# ───────────────────────────── archivos de muestra ─────────────────────────────
def pdf_minimo(titulo, lineas):
    esc = lambda s: s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")  # noqa: E731
    txt = f"BT /F1 18 Tf 56 780 Td ({esc(titulo)}) Tj /F1 11 Tf"
    for l in lineas:
        txt += f" 0 -22 Td ({esc(l)}) Tj"
    txt += " ET"
    cont = txt.encode("latin-1", "replace")
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
            b"/Resources << /Font << /F1 5 0 R >> >> >>",
            b"<< /Length " + str(len(cont)).encode() + b" >>\nstream\n" + cont + b"\nendstream",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"]
    out, offs = b"%PDF-1.4\n", []
    for i, o in enumerate(objs, 1):
        offs.append(len(out))
        out += f"{i} 0 obj\n".encode() + o + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offs)
    return out + f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()


def png_muestra(ancho=160, alto=120, tono=(70, 130, 180)):
    filas = b""
    for y in range(alto):
        fila = b"\x00"
        for x in range(ancho):
            dentro = (x - ancho / 2) ** 2 / (ancho / 3) ** 2 + (y - alto / 2) ** 2 / (alto / 3) ** 2 < 1
            k = 1.0 if dentro else 0.35 + 0.4 * y / alto
            fila += bytes(int(c * k) for c in (tono if dentro else (200, 200, 205)))
        filas += fila

    def chunk(tipo, datos):
        c = tipo + datos
        return struct.pack(">I", len(datos)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", ancho, alto, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(filas)) + chunk(b"IEND", b""))


def stl_muela(nombre, a=7.0, b=6.0, c=8.0, seg=28, anillos=16):
    """STL ASCII de una 'muela' (elipsoide con cúspides) para probar el visor 3D."""
    def punto(i, j):
        th, ph = math.pi * i / anillos, 2 * math.pi * j / seg
        cuspide = 1 + 0.18 * math.sin(4 * ph) * math.sin(th) ** 2 * (1 if th < math.pi / 2 else 0.3)
        return (a * math.sin(th) * math.cos(ph) * cuspide,
                b * math.sin(th) * math.sin(ph) * cuspide,
                c * math.cos(th))

    def normal(p, q, r):
        u, v = [q[k] - p[k] for k in range(3)], [r[k] - p[k] for k in range(3)]
        n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        m = math.sqrt(sum(x * x for x in n)) or 1.0
        return tuple(x / m for x in n)

    out = [f"solid {nombre}"]
    for i in range(anillos):
        for j in range(seg):
            p00, p01 = punto(i, j), punto(i, (j + 1) % seg)
            p10, p11 = punto(i + 1, j), punto(i + 1, (j + 1) % seg)
            for tri in ((p00, p10, p01), (p01, p10, p11)):
                n = normal(*tri)
                out.append(f"facet normal {n[0]:.4f} {n[1]:.4f} {n[2]:.4f}\n outer loop")
                out += [f"  vertex {v[0]:.4f} {v[1]:.4f} {v[2]:.4f}" for v in tri]
                out.append(" endloop\nendfacet")
    out.append(f"endsolid {nombre}")
    return "\n".join(out).encode()


def multipart(campos, campo_archivo, nombre, contenido, ctype):
    b = uuid.uuid4().hex
    cuerpo = b""
    for k, v in campos.items():
        cuerpo += f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    cuerpo += (f'--{b}\r\nContent-Disposition: form-data; name="{campo_archivo}"; filename="{nombre}"\r\n'
               f"Content-Type: {ctype}\r\n\r\n").encode() + contenido + b"\r\n"
    return cuerpo + f"--{b}--\r\n".encode(), f"multipart/form-data; boundary={b}"


# ───────────────────────────── datos de muestra ─────────────────────────────
USUARIOS_NUEVOS = [("Marcos", "Peralta (demo)", "tecnico2", "TECNICO"),
                   ("Lucía", "Benítez (demo)", "tecnico3", "TECNICO"),
                   ("Sofía", "Arce (demo)", "administrativa", "ADMINISTRATIVO")]

MATERIALES_NUEVOS = [  # nombre, categoría, stock, mínimo, unidad, precio, proveedor, descuenta
    ("Aleación Cromo-Cobalto", "METAL", 2500, 600, "g", 95, "Metales y Aleaciones Dentales", True),
    ("Revestimiento Fosfatado", "CERAMICA", 10, 4, "kg", 18500, "Distribuidora Dental Central", True),
    ("Acrílico Termocurado", "ACRILICO", 6, 3, "kit", 24800, "Casa Dental Norte", True),
    ("Pulidor Diamantado", "CONSUMIBLE", 2, 5, "unidad", 7200, "Casa Dental Norte", True),   # bajo mínimo
    ("Barniz Cerámico", "CERAMICA", 1, 3, "frasco", 15400, "Distribuidora Dental Central", True),  # bajo mínimo
    ("Guantes y barbijos", "CONSUMIBLE", 40, 10, "caja", 9800, "Casa Dental Norte", False),  # no descuenta
]
REPOSICION = {"Yeso Piedra Tipo IV": 30, "Porcelana Estratificada": 8, "Resina Autopolimerizable": 8,
              "Alambre Inoxidable 0.7mm": 10, "Discos de Zirconia": 20, "Cera para Encerado": 6,
              "Adhesivo Dental": 10, "Fresas y Discos de Corte": 20, "Separadores de Goma": 10}

Y, P, R, A, C = ("Yeso Piedra Tipo IV", "Porcelana Estratificada", "Resina Autopolimerizable",
                 "Alambre Inoxidable 0.7mm", "Cera para Encerado")
Z, AD, F, S, CR = ("Discos de Zirconia", "Adhesivo Dental", "Fresas y Discos de Corte",
                   "Separadores de Goma", "Aleación Cromo-Cobalto")
RECETAS = {  # trabajo -> [(material, cantidad)]
    "Corona de Zirconio": [(Z, 1), (P, 0.1), (Y, 0.2), (AD, 0.05), (F, 0.2)],
    "Corona de Porcelana sobre Metal": [(CR, 12), (P, 0.15), (Y, 0.25), (C, 0.2)],
    "Puente de Porcelana (3 piezas)": [(CR, 30), (P, 0.4), (Y, 0.5), (C, 0.5)],
    "Carilla de Porcelana": [(P, 0.1), (Y, 0.15), (AD, 0.1)],
    "Placa de Ortodoncia Removible": [(R, 0.3), (A, 0.2), (Y, 0.4), (S, 0.1)],
    "Retenedor de Hawley": [(A, 0.15), (R, 0.2), (Y, 0.2)],
    "Retenedor Fijo Lingual": [(A, 0.1), (AD, 0.1)],
    "Placa Miorelajante": [(R, 0.4), (Y, 0.3), (F, 0.2)],
    "Placa de Reposicionamiento": [(R, 0.5), (A, 0.2), (Y, 0.4)],
    "Aparato de ATM Completo": [(R, 0.7), (A, 0.4), (Y, 0.6), (F, 0.4)],
}
DIAS_POR_CATEGORIA = {"FIJA": 10, "REMOVIBLE": 7, "ORTODONCIA": 7, "ATM": 8, "PERSONALIZADO": 14}

PROVEEDORES_NUEVOS = [
    ("Laboratorio de Fresado CAD/CAM Sur", "30-71234567-4", "ventas@cadcam-sur.demo.test", "Av. Circunvalación 4500, Córdoba"),
    ("Insumos Ortodónticos del Centro", "30-70987654-1", "pedidos@ortocentro.demo.test", "Obispo Trejo 320, Córdoba"),
]

ODONTOLOGOS = [  # nombre, DNI, CUIT, matrícula, clínica, dirección (todo ficticio)
    ("Dr. Ignacio Vázquez", "27111001", "20-27111001-4", "MP 4101", "Clínica Dental Vázquez y Asoc.", "Av. Colón 1234, Córdoba"),
    ("Dra. Camila Ortega", "31222002", "27-31222002-6", "MP 4102", "Consultorio Sonrisas", "Bv. Illia 450, Córdoba"),
    ("Dr. Federico Maldonado", "26333003", "20-26333003-8", "MP 4103", "Centro Odontológico del Parque", "Av. Hipólito Yrigoyen 780, Córdoba"),
    ("Dra. Julieta Funes", "33444004", "27-33444004-1", "MP 4104", "Ortodoncia Funes", "27 de Abril 615, Córdoba"),
    ("Dr. Matías Cabrera", "29555005", "20-29555005-3", "MP 4105", "Cabrera Implantes", "Av. Vélez Sarsfield 1500, Córdoba"),
    ("Dra. Agustina Paz", "34666006", "27-34666006-5", "MP 4106", "Odontopediatría Paz", "Duarte Quirós 920, Córdoba"),
    ("Dr. Santiago Luna", "28777007", "20-28777007-7", "MP 4107", "Estudio Luna", "Av. Rafael Núñez 3800, Córdoba"),
    ("Dra. Romina Aguirre", "32888008", "27-32888008-9", "MP 4108", "Clínica Aguirre Salud Dental", "Pueyrredón 260, Córdoba"),
    ("Dr. Emilio Ferreyra", "25999009", "20-25999009-0", "MP 4109", "Consultorio Ferreyra", "Av. General Paz 98, Córdoba"),
    ("Dra. Natalia Cornejo", "35101010", "27-35101010-2", "MP 4110", "Centro ATM Córdoba", "Av. Castro Barros 1100, Córdoba"),
]

Zr, PM, PU, CA = "Corona de Zirconio", "Corona de Porcelana sobre Metal", "Puente de Porcelana (3 piezas)", "Carilla de Porcelana"
PO, RH, RF = "Placa de Ortodoncia Removible", "Retenedor de Hawley", "Retenedor Fijo Lingual"
MI, PR, AT, TM = "Placa Miorelajante", "Placa de Reposicionamiento", "Aparato de ATM Completo", "Trabajo a Medida"
RECIBIDO, EN_PROCESO, CONTROL, LISTO, ENTREGADO, CANCELADO = ("RECIBIDO", "EN_PROCESO", "CONTROL", "LISTO",
                                                               "ENTREGADO", "CANCELADO")
# (odontólogo#, paciente, trabajo, técnico, estado, prioridad, entrega en N días | "hace N días" si ENTREGADO, nota)
PEDIDOS = [
    (3, "Valentina Roldán", Zr, None, RECIBIDO, "URGENTE", 4, "Paciente con evento: necesita la prueba antes."),
    (1, "Hernán Quiroga", PM, None, RECIBIDO, "NORMAL", 9, None),
    (7, "Mariela Sosa", PO, None, RECIBIDO, "NORMAL", 12, None),
    (0, "Tomás Elizondo", CA, None, RECIBIDO, "NORMAL", 8, "Color A2, 4 carillas. Se envía guía de color."),
    (9, "Paula Benavídez", MI, None, RECIBIDO, "NORMAL", 6, None),
    (0, "Gustavo Ledesma", PU, "t1", EN_PROCESO, "NORMAL", 7, None),
    (2, "Silvia Montenegro", Zr, "t2", EN_PROCESO, "URGENTE", 2, "La paciente viaja el viernes."),
    (4, "Diego Ibarra", PM, "t1", EN_PROCESO, "NORMAL", -3, "Se atrasó por falta de aleación."),
    (5, "Lara Gutiérrez", RH, "t3", EN_PROCESO, "NORMAL", 5, None),
    (8, "Néstor Salinas", AT, "t2", EN_PROCESO, "NORMAL", 10, None),
    (3, "Carolina Vera", PR, "t3", EN_PROCESO, "NORMAL", 4, None),
    (6, "Julián Arce", TM, "t1", EN_PROCESO, "NORMAL", 14, "Guarda oclusal con retención extra."),
    (1, "Ramiro Cáceres", CA, "t2", CONTROL, "NORMAL", 1, "Verificar ajuste marginal."),
    (2, "Estela Páez", PU, "t1", CONTROL, "URGENTE", -1, None),
    (9, "Marcos Delgado", MI, "t3", CONTROL, "NORMAL", 3, None),
    (7, "Brenda Olivera", PO, "t2", CONTROL, "NORMAL", 2, None),
    (0, "Rosa Heredia", Zr, "t1", LISTO, "NORMAL", 0, "Pulido final aprobado."),
    (4, "Facundo Luna", PM, "t2", LISTO, "NORMAL", -2, "Esperando que lo retiren."),
    (5, "Daniela Ponce", RF, "t3", LISTO, "NORMAL", 1, None),
    (3, "Oscar Mansilla", AT, "t1", LISTO, "URGENTE", 0, None),
    (8, "Irene Sandoval", CA, "t2", LISTO, "NORMAL", -1, None),
    (0, "Sebastián Ríos", Zr, "t1", ENTREGADO, "NORMAL", 2, None),
    (1, "Marina Acuña", PU, "t2", ENTREGADO, "NORMAL", 4, None),
    (2, "Pablo Ferrari", PM, "t1", ENTREGADO, "NORMAL", 6, None),
    (3, "Gabriela Toledo", PR, "t3", ENTREGADO, "NORMAL", 9, None),
    (4, "Walter Moyano", Zr, "t2", ENTREGADO, "NORMAL", 12, None),
    (5, "Cecilia Barrios", PO, "t1", ENTREGADO, "NORMAL", 15, None),
    (6, "Emanuel Soria", MI, "t3", ENTREGADO, "NORMAL", 19, None),
    (0, "Alicia Domínguez", CA, "t2", ENTREGADO, "NORMAL", 23, None),
    (7, "Ricardo Peña", RH, "t1", ENTREGADO, "NORMAL", 28, None),
    (2, "Noelia Figueroa", PM, "t3", ENTREGADO, "NORMAL", 33, None),
    (9, "Lucas Medina", AT, "t2", ENTREGADO, "NORMAL", 38, None),
    (4, "Andrea Villarreal", PU, "t1", ENTREGADO, "NORMAL", 42, None),
    (8, "Cristian Bustos", Zr, "t2", CANCELADO, "NORMAL", 10, "Cancelado: el paciente cambió de tratamiento."),
]
RETIRA = ["Cadetería del consultorio", "La asistente del consultorio", "El propio odontólogo", "Mensajería Rápida SA"]

MOVIMIENTOS_CAJA = [  # días atrás, tipo, caja, concepto, monto
    (45, "INGRESO", "FISICA", "Saldo inicial de caja", 350000),
    (45, "INGRESO", "BANCARIA", "Saldo inicial de la cuenta", 1200000),
    (38, "EGRESO", "BANCARIA", "Alquiler del local", 280000),
    (36, "EGRESO", "BANCARIA", "Electricidad (EPEC)", 46800),
    (34, "EGRESO", "BANCARIA", "Internet y teléfono", 22900),
    (30, "EGRESO", "FISICA", "Limpieza y artículos de higiene", 14500),
    (27, "INGRESO", "FISICA", "Cobro de trabajo particular (paciente directo)", 38000),
    (24, "EGRESO", "FISICA", "Mantenimiento del compresor", 31500),
    (20, "EGRESO", "BANCARIA", "Gas natural", 18700),
    (16, "INGRESO", "BANCARIA", "Transferencia por reparación de prótesis", 42000),
    (13, "EGRESO", "FISICA", "Flete y envíos a consultorios", 12300),
    (9, "EGRESO", "BANCARIA", "Alquiler del local", 280000),
    (7, "EGRESO", "BANCARIA", "Contador (honorarios)", 65000),
    (5, "EGRESO", "FISICA", "Café, agua y librería", 9400),
    (3, "INGRESO", "FISICA", "Venta de modelos de estudio", 17500),
    (2, "EGRESO", "BANCARIA", "Impuestos municipales", 24600),
]

DEUDAS = [  # proveedor, descripción, monto, vence en días, nº de factura, pagar con caja
    ("Distribuidora Dental Central", "Compra de yeso y revestimiento", 86500, 10, "0003-00012871", None),
    ("Distribuidora Dental Central", "Discos de zirconia x10", 214000, -6, "0003-00012640", None),
    ("Casa Dental Norte", "Resina y alambre ortodóncico", 58300, 20, "0011-00004412", None),
    ("Casa Dental Norte", "Fresas de carburo", 31900, 3, "0011-00004298", "FISICA"),
    ("Metales y Aleaciones Dentales", "Aleación Cr-Co 1 kg", 142000, 14, "0005-00099120", None),
    ("Metales y Aleaciones Dentales", "Aleación Cr-Co 500 g", 76000, -20, "0005-00098544", "BANCARIA"),
    ("Laboratorio de Fresado CAD/CAM Sur", "Fresado de estructuras de zirconia (lote 12)", 128000, 7, "0002-00001877", None),
]

EMPLEADOS = [("tecnico1", "SEMANAL", 180000), ("tecnico2", "QUINCENAL", 350000),
             ("tecnico3", "DIARIO", 30000), ("administrativa", "MENSUAL", 650000)]


# ───────────────────────────── el programa ─────────────────────────────
def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--base", default="https://127.0.0.1")
    ap.add_argument("--host", default="", help="Host header (el dominio) si --base es una IP")
    ap.add_argument("--mysql-container", default="", help="para el ajuste de fechas por SQL")
    ap.add_argument("--credenciales", default=os.path.expanduser("~/demo-credenciales.txt"))
    ap.add_argument("--forzar", action="store_true", help="correr aunque ya haya odontólogos")
    args = ap.parse_args()

    password = os.environ.get("GS_ADMIN_PASSWORD", "")
    bot_key = os.environ.get("GS_BOT_API_KEY", "")
    if not password:
        sys.exit("Falta GS_ADMIN_PASSWORD en el entorno.")
    api = Api(args.base, args.host)

    paso("Login como admin")
    api.call("POST", "/api/auth/login", {"username": "admin", "password": password})
    if lista(api.call("GET", "/api/odontologos")) and not args.forzar:
        sys.exit("Ya hay odontólogos cargados: no se vuelve a cargar (usá --forzar si es a propósito).")

    # ── usuarios ──
    paso("Usuarios de muestra (contraseñas al azar → archivo con permisos 600)")
    creds = []
    for nombre, apellido, user, rol in USUARIOS_NUEVOS:
        pw = secrets.token_urlsafe(12)
        try:
            api.call("POST", "/api/auth/register", {"nombre": nombre, "apellido": apellido,
                                                     "username": user, "password": pw, "rol": rol})
            creds.append((user, rol, pw))
            print(f"   + {user} ({rol})")
        except ApiError as e:
            if e.status != 409:
                raise
            print(f"   = {user} ya existía")
    if creds:
        fd = os.open(args.credenciales, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write("# Usuarios de muestra de G&S: borralos o cambiales la contraseña antes del uso real.\n")
            f.writelines(f"{u}\t{r}\t{p}\n" for u, r, p in creds)
        print(f"   contraseñas en {args.credenciales}")
    usuarios = {u["username"]: u for u in lista(api.call("GET", "/api/auth/usuarios"))}
    tecnicos = {k: usuarios.get(n) for k, n in (("t1", "tecnico1"), ("t2", "tecnico2"), ("t3", "tecnico3"))}
    nombre_de = lambda u: f"{u['nombre']} {u['apellido']}".strip()  # noqa: E731

    # ── proveedores ──
    paso("Proveedores")
    provs = {p["nombre"]: p for p in lista(api.call("GET", "/api/finanzas/proveedores"))}
    for nombre, cuit, email, direccion in PROVEEDORES_NUEVOS:
        if nombre not in provs:
            p = intentar(f"proveedor {nombre}", lambda: api.call(
                "POST", "/api/finanzas/proveedores",
                {"nombre": nombre, "cuit": cuit, "email": email, "telefono": "", "direccion": direccion}))
            if p:
                provs[nombre] = p
    print(f"   {len(provs)} proveedores")

    # ── caja inicial (antes de pagar nada) ──
    paso("Caja: movimientos de los últimos 45 días")
    for dias, tipo, caja, concepto, monto in MOVIMIENTOS_CAJA:
        intentar(f"caja {concepto}", lambda: api.call("POST", "/api/finanzas/cajas/movimiento", {
            "tipo": tipo, "tipoCaja": caja, "concepto": concepto, "monto": monto,
            "fechaMovimiento": fecha(-dias), "creadoPor": "admin"}))

    # ── stock ──
    paso("Stock: materiales nuevos, reposición y movimientos")
    mats = {m["nombre"]: m for m in lista(api.call("GET", "/api/stock"))}
    for nombre, cat, stock, minimo, unidad, precio, prov, descuenta in MATERIALES_NUEVOS:
        if nombre not in mats:
            m = intentar(f"material {nombre}", lambda: api.call("POST", "/api/stock", {
                "nombre": nombre, "descripcion": "Material de muestra", "categoria": cat, "stockActual": stock,
                "stockMinimo": minimo, "unidadMedida": unidad, "precioUnitario": precio, "proveedor": prov,
                "descuentaStock": descuenta}))
            if m:
                mats[nombre] = m
    for nombre, cantidad in REPOSICION.items():
        if nombre in mats:
            intentar(f"reposición {nombre}", lambda: api.call("POST", "/api/stock/movimiento", {
                "materialId": mats[nombre]["id"], "materialNombre": nombre, "tipo": "ENTRADA",
                "cantidad": cantidad, "motivo": "Compra a proveedor (muestra)"}))
        else:
            print(f"   ! no existe el material «{nombre}»: me lo salteo")
    if Y in mats:
        intentar("salida manual", lambda: api.call("POST", "/api/stock/movimiento", {
            "materialId": mats[Y]["id"], "materialNombre": Y, "tipo": "SALIDA", "cantidad": 1.5,
            "motivo": "Merma por modelo roto"}))
    if CR in mats:
        intentar("ajuste de inventario", lambda: api.call("POST", "/api/stock/movimiento", {
            "materialId": mats[CR]["id"], "materialNombre": CR, "tipo": "AJUSTE", "cantidad": 2400,
            "motivo": "Ajuste por conteo físico"}))

    # ── catálogo con recetas ──
    paso("Catálogo: recetas (qué material consume cada trabajo)")
    catalogo = {t["nombre"]: t for t in lista(api.call("GET", "/api/catalogo"))}
    for nombre, ingredientes in RECETAS.items():
        t = catalogo.get(nombre)
        if not t:
            print(f"   ! no existe el trabajo «{nombre}»: me lo salteo")
            continue
        receta = [{"materialId": mats[m]["id"], "materialNombre": m, "cantidad": c,
                   "unidad": mats[m].get("unidadMedida") or "unidad"} for m, c in ingredientes if m in mats]
        intentar(f"receta {nombre}", lambda: api.call("PUT", f"/api/catalogo/{t['id']}", {
            "nombre": t["nombre"], "descripcion": t.get("descripcion"), "precio": t["precio"],
            "categoria": t["categoria"], "fotoUrl": t.get("fotoUrl"), "receta": receta,
            "tiempoEstimadoDias": t.get("tiempoEstimadoDias") or DIAS_POR_CATEGORIA.get(t["categoria"], 7)}))

    # ── odontólogos ──
    paso("Odontólogos (sin teléfono a propósito: no se manda WhatsApp a nadie)")
    odos = []
    for i, (nombre, dni, cuit, mat, clinica, direccion) in enumerate(ODONTOLOGOS):
        slug = nombre.split()[-1].lower().replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")
        o = api.call("POST", "/api/odontologos", {
            "nombre": nombre, "dni": dni, "cuit": cuit, "telefono": "", "email": f"{slug}@demo.gs.test",
            "matricula": mat, "clinica": clinica, "direccion": direccion})
        odos.append(o)
    print(f"   {len(odos)} odontólogos")

    # ── pedidos ──
    paso("Pedidos: crear, avanzar de estado y entregar")
    pedidos, atrasados = [], []
    for idx, (od, paciente, trabajo, tec, estado, prio, dias, nota) in enumerate(PEDIDOS):
        t_cat = catalogo.get(trabajo)
        u_tec = tecnicos.get(tec) if tec else None
        precio = (t_cat or {}).get("precio") or 0
        if trabajo == TM:
            precio = 95000
        elif idx % 5 == 2 and precio:
            precio = round(precio * 1.08, -2)  # no todos los pedidos salen a precio de lista
        entregado = estado == ENTREGADO
        body = {"odontologoId": odos[od]["id"], "odontologoNombre": odos[od]["nombre"], "paciente": paciente,
                "catalogoTrabajoId": (t_cat or {}).get("id"), "trabajo": trabajo, "fechaEntrega": fecha(max(dias, 1) if not entregado else 1),
                "prioridad": prio, "precioAcordado": precio, "observaciones": nota}
        if u_tec:
            body.update(tecnicoId=u_tec["id"], tecnicoNombre=nombre_de(u_tec))
        p = intentar(f"pedido {paciente}", lambda: api.call("POST", "/api/pedidos", body))
        if not p:
            continue
        pid = p["id"]
        pedidos.append({"id": pid, "nro": p["nroPedido"], "estado": estado, "od": od, "trabajo": trabajo})
        if not entregado and dias <= 0 and estado != CANCELADO:
            atrasados.append((pid, dias))
        camino = {RECIBIDO: [], EN_PROCESO: [EN_PROCESO], CONTROL: [EN_PROCESO, CONTROL],
                  LISTO: [EN_PROCESO, CONTROL, LISTO], ENTREGADO: [EN_PROCESO, CONTROL, LISTO],
                  CANCELADO: [CANCELADO]}[estado]
        for e in camino:
            intentar(f"{p['nroPedido']} → {e}", lambda: api.call("PATCH", f"/api/pedidos/{pid}/estado", query={"nuevoEstado": e}))
        if entregado:
            monto = round(precio * 0.9, -2) if idx % 7 == 0 else None  # 1 de cada 7, con descuento
            entrega = {"retiradoPor": RETIRA[idx % len(RETIRA)], "fechaEntregaReal": fecha(-dias),
                       "observacionesEntrega": "Entrega sin novedades."}
            if monto:
                entrega["monto"] = monto
            intentar(f"{p['nroPedido']} entrega", lambda: api.call("PATCH", f"/api/pedidos/{pid}/entregar", entrega))
    por_estado = {}
    for p in pedidos:
        por_estado[p["estado"]] = por_estado.get(p["estado"], 0) + 1
    print(f"   {len(pedidos)} pedidos: {por_estado}")

    # ── documentos y escaneos 3D ──
    paso("Documentos y escaneos 3D de muestra")
    png = png_muestra()
    for k, p in enumerate(pedidos[:14]):
        if p["estado"] == CANCELADO:
            continue
        pdf = pdf_minimo(f"Orden de trabajo {p['nro']}", [
            f"Trabajo: {p['trabajo']}", f"Odontólogo: {ODONTOLOGOS[p['od']][0]}",
            "Documento de muestra generado para probar la carga de archivos."])
        d, ct = multipart({}, "file", f"orden-{p['nro']}.pdf", pdf, "application/pdf")
        intentar(f"PDF {p['nro']}", lambda: api.call("POST", f"/api/pedidos/{p['id']}/docs", datos=d, ctype=ct))
        if k % 2 == 0:
            d, ct = multipart({}, "file", f"foto-modelo-{p['nro']}.png", png, "image/png")
            intentar(f"imagen {p['nro']}", lambda: api.call("POST", f"/api/pedidos/{p['id']}/docs", datos=d, ctype=ct))
        if k % 3 == 0:
            d, ct = multipart({"descripcion": "Arcada superior (muestra)"}, "file",
                              f"arcada-superior-{p['nro']}.stl", stl_muela(f"muela{k}"), "model/stl")
            intentar(f"escaneo {p['nro']}", lambda: api.call("POST", f"/api/pedidos/{p['id']}/escaneos", datos=d, ctype=ct))

    # ── fechas históricas (la API no deja poner fechas pasadas en pedidos/deudas) ──
    paso("Ajuste de fechas para que haya historial")
    sql(args, """
        UPDATE pedidos SET fecha_creacion = TIMESTAMP(DATE_SUB(fecha_entrega_real, INTERVAL (5 + id MOD 7) DAY), '09:30:00'),
               fecha_entrega = DATE_ADD(fecha_entrega_real, INTERVAL ((id MOD 3) - 1) DAY),
               fecha_ultima_modificacion = TIMESTAMP(fecha_entrega_real, '16:00:00')
         WHERE estado = 'ENTREGADO' AND fecha_entrega_real IS NOT NULL;
        UPDATE pedidos SET fecha_creacion = TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL (1 + id MOD 9) DAY), '10:15:00')
         WHERE estado <> 'ENTREGADO';
        UPDATE comprobantes c JOIN pedidos p ON p.id = c.pedido_id
           SET c.fecha_emision = p.fecha_entrega_real,
               c.fecha_vencimiento = DATE_ADD(p.fecha_entrega_real, INTERVAL 30 DAY),
               c.fecha_creacion = TIMESTAMP(p.fecha_entrega_real, '17:00:00')
         WHERE p.fecha_entrega_real IS NOT NULL;
        UPDATE movimientos_stock m JOIN pedidos p ON p.id = m.pedido_id
           SET m.fecha_movimiento = DATE_ADD(p.fecha_creacion, INTERVAL 1 DAY)
         WHERE m.pedido_id IS NOT NULL;""")
    for pid, dias in atrasados:
        sql(args, f"UPDATE pedidos SET fecha_entrega = DATE_ADD(CURDATE(), INTERVAL {dias} DAY) WHERE id = {pid};")
    print(f"   {len(atrasados)} pedidos con fecha de entrega vencida o de hoy")

    # ── deudas con proveedores ──
    paso("Deudas con proveedores")
    for prov, desc, monto, vence, nro, caja in DEUDAS:
        if prov not in provs:
            print(f"   ! falta el proveedor «{prov}»")
            continue
        deuda = intentar(f"deuda {desc}", lambda: api.call("POST", "/api/finanzas/proveedores/deudas", {
            "proveedorId": provs[prov]["id"], "descripcion": desc, "monto": monto, "fechaVencimiento": fecha(vence),
            "nroFacturaProveedor": nro, "observaciones": "Deuda de muestra"}))
        if deuda and caja:
            intentar(f"pago deuda {desc}", lambda: api.call(
                "PATCH", f"/api/finanzas/proveedores/deudas/{deuda['id']}/pagar", query={"caja": caja}))

    # ── cobros a odontólogos ──
    paso("Cuentas corrientes: cobros parciales y totales")
    cuentas = sorted(lista(api.call("GET", "/api/finanzas/cuentas-corrientes")),
                     key=lambda c: -float(c["totalDeuda"]))
    plan = [(None, None, 0), (0.5, "TRANSFERENCIA", 5), (1.0, "EFECTIVO", 8), (0.35, "EFECTIVO", 2), (1.0, "TRANSFERENCIA", 11)]
    for cuenta, (parte, medio, hace) in zip(cuentas, plan):
        if parte is None:
            continue
        monto = round(float(cuenta["totalDeuda"]) * parte, -2) or float(cuenta["totalDeuda"])
        intentar(f"cobro {cuenta['odontologoNombre']}", lambda: api.call(
            "POST", f"/api/finanzas/odontologos/{cuenta['odontologoId']}/pagos",
            {"monto": monto, "medio": medio, "fecha": fecha(-hace), "nota": "Cobro de muestra"}))
    print(f"   {len(cuentas)} odontólogos con deuda; los demás quedan sin cobrar (para ver la mora)")
    prov_central = provs.get("Distribuidora Dental Central")
    for c in cuentas[5:6]:
        if prov_central:
            intentar("pago triangulado a proveedor", lambda: api.call(
                "POST", f"/api/finanzas/sueldos/odontologos/{c['odontologoId']}/pago-proveedor",
                {"proveedorId": prov_central["id"], "monto": 40000, "nota": "El odontólogo pagó al proveedor por nosotros"}))

    # ── sueldos ──
    paso("Sueldos y empleados")
    for user, frec, base in EMPLEADOS:
        u = usuarios.get(user)
        if not u:
            continue

        def alta_o_config(u=u, frec=frec, base=base):
            try:
                api.call("POST", "/api/finanzas/sueldos/empleados", {
                    "usuarioId": u["id"], "nombre": nombre_de(u), "rol": u["rol"], "telefono": "",
                    "frecuencia": frec, "montoBase": base})
            except ApiError as e:
                if e.status != 409:
                    raise
                # Los usuarios recién registrados ya quedan dados de alta en sueldos:
                # solo hay que fijarles la frecuencia y el monto.
                api.call("PUT", f"/api/finanzas/sueldos/empleados/{u['id']}/config",
                         {"frecuencia": frec, "montoBase": base})
        intentar(f"empleado {user}", alta_o_config)
    intentar("devengar", lambda: api.call("POST", "/api/finanzas/sueldos/devengar-ahora"))
    for user, monto, hace, nota in (("tecnico1", 180000, 6, "Semana cerrada"), ("tecnico2", 200000, 14, "Adelanto de quincena")):
        u = usuarios.get(user)
        if u:
            intentar(f"sueldo {user}", lambda: api.call("POST", "/api/finanzas/sueldos/pago", {
                "usuarioId": u["id"], "monto": monto, "manejoSobrante": "DESCONTAR_PROXIMO",
                "fecha": fecha(-hace), "nota": nota}))

    # ── pagos que "llegan por el bot de WhatsApp" ──
    paso("Bot de WhatsApp (simulado: mismo endpoint que usa el bot)")
    if bot_key:
        h = {"X-Bot-Api-Key": bot_key}
        t2 = usuarios.get("tecnico2")
        emisor = ODONTOLOGOS[0][0].replace("Dr. ", "")
        mini = base64.b64encode(png_muestra(120, 80, (60, 160, 90))).decode()
        if t2:
            intentar("bot: transferencia a empleado", lambda: api.call(
                "POST", "/api/finanzas/sueldos/pago-automatico", headers=h, cuerpo={
                    "receptorNombre": nombre_de(t2), "emisor": emisor, "monto": 120000, "fecha": fecha(-1),
                    "cargadoPorNombre": "Rebeca González", "grupoOrigen": "Pagos G&S (muestra)",
                    "idOperacion": "DEMO-OP-0001", "nota": "Comprobante de muestra",
                    "comprobanteBase64": mini, "comprobanteMime": "image/png", "comprobanteNombre": "comprobante-0001.png"}))
            intentar("bot: comprobante duplicado", lambda: api.call(
                "POST", "/api/finanzas/sueldos/pago-automatico", headers=h, cuerpo={
                    "receptorNombre": nombre_de(t2), "emisor": emisor, "monto": 120000, "fecha": fecha(-1),
                    "grupoOrigen": "Pagos G&S (muestra)", "idOperacion": "DEMO-OP-0001"}))
        intentar("bot: receptor desconocido", lambda: api.call(
            "POST", "/api/finanzas/sueldos/pago-automatico", headers=h, cuerpo={
                "receptorNombre": "Persona Desconocida", "emisor": emisor, "monto": 15000, "fecha": fecha(-2),
                "grupoOrigen": "Pagos G&S (muestra)", "idOperacion": "DEMO-OP-0002"}))
        for monto, receptor in ((25000, usuarios.get("tecnico3")), (18000, usuarios.get("tecnico1")), (9000, usuarios.get("tecnico3"))):
            if receptor:
                intentar("bot: efectivo pendiente", lambda: api.call(
                    "POST", "/api/finanzas/sueldos/pago-efectivo", headers=h, cuerpo={
                        "receptorNombre": nombre_de(receptor), "monto": monto, "emisor": emisor,
                        "cargadoPorNombre": "Rebeca González", "grupoOrigen": "Pagos G&S (muestra)"}))
        pend = lista(intentar("pendientes efectivo", lambda: api.call("GET", "/api/finanzas/sueldos/pendientes-efectivo")))
        if len(pend) >= 1:
            intentar("confirmar efectivo", lambda: api.call("POST", f"/api/finanzas/sueldos/registros-bot/{pend[0]['id']}/confirmar"))
        if len(pend) >= 2:
            intentar("rechazar efectivo", lambda: api.call("POST", f"/api/finanzas/sueldos/registros-bot/{pend[1]['id']}/rechazar",
                                                           {"motivo": "El monto no coincide con lo cargado"}))
    else:
        print("   (sin GS_BOT_API_KEY: me salteo la simulación del bot)")

    # ── reportes ──
    paso("Reportes mensuales")
    mes_anterior = (HOY.replace(day=1) - timedelta(days=1))
    for anio, mes in ((HOY.year, HOY.month), (mes_anterior.year, mes_anterior.month)):
        intentar(f"reporte {anio}-{mes:02d}", lambda: api.call("POST", "/api/finanzas/reportes/generar", query={"anio": anio, "mes": mes}))

    # ── resumen ──
    paso("Resumen")
    resumen = [("odontólogos", "/api/odontologos"), ("pedidos", "/api/pedidos"), ("catálogo", "/api/catalogo"),
               ("materiales", "/api/stock"), ("comprobantes", "/api/finanzas/comprobantes"),
               ("proveedores", "/api/finanzas/proveedores"), ("registros del bot", "/api/finanzas/sueldos/registros-bot"),
               ("reportes", "/api/finanzas/reportes")]
    for nombre, ruta in resumen:
        r = intentar(f"contar {nombre}", lambda: api.call("GET", ruta))
        print(f"   {nombre:<20}{len(lista(r)) if r is not None else '?'}")
    alertas = intentar("alertas de stock", lambda: api.call("GET", "/api/stock/alertas"))
    print(f"   {'materiales bajo mínimo':<20}{len(lista(alertas)) if alertas is not None else '?'}")
    print("\n" + (f"Terminó con {len(FALLAS)} paso(s) fallido(s):\n  - " + "\n  - ".join(FALLAS) if FALLAS else "Terminó sin errores."))
    return 1 if FALLAS else 0


if __name__ == "__main__":
    sys.exit(main())
