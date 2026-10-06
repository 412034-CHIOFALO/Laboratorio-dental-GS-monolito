"""Genera los comprobantes de prueba (imágenes) con la estructura de las billeteras que llegan en la práctica.

No son capturas reales (no hay datos de nadie): reproducen el DISEÑO — etiquetas, orden, el monto
partido en líneas de Personal Pay, etc. — con datos inventados. Sirven para probar el OCR + los parsers
de punta a punta (test/ocr.test.js). Correr: python test/generar_fixtures.py
"""
import os
import random

from PIL import Image, ImageDraw, ImageFilter, ImageFont

AQUI = os.path.dirname(os.path.abspath(__file__))
SALIDA = os.path.join(AQUI, "fixtures")
os.makedirs(SALIDA, exist_ok=True)
FUENTES = r"C:\Windows\Fonts"


def fuente(tam, negrita=False):
    for nombre in (("arialbd.ttf" if negrita else "arial.ttf"), "DejaVuSans-Bold.ttf" if negrita else "DejaVuSans.ttf"):
        for base in (FUENTES, "/usr/share/fonts/truetype/dejavu"):
            ruta = os.path.join(base, nombre)
            if os.path.exists(ruta):
                return ImageFont.truetype(ruta, tam)
    return ImageFont.load_default()


def pantalla(lineas, fondo=(255, 255, 255), ancho=720, alto=1280):
    """lineas: [(texto, tamaño, negrita, color, espacio_antes)]"""
    img = Image.new("RGB", (ancho, alto), fondo)
    d = ImageDraw.Draw(img)
    y = 70
    for texto, tam, neg, color, antes in lineas:
        y += antes
        d.text((50, y), texto, font=fuente(tam, neg), fill=color)
        y += int(tam * 1.35)
    return img


GRIS, NEGRO, AZUL = (110, 110, 110), (20, 20, 20), (0, 110, 200)

# 1) Mercado Pago: etiqueta sola en su línea, nombre en la siguiente
mp = pantalla([
    ("Transferencia enviada", 40, True, NEGRO, 0),
    ("$ 85.000", 64, True, NEGRO, 25),
    ("12 de septiembre de 2026 - 14:32 h", 26, False, GRIS, 10),
    ("Para", 28, False, GRIS, 60),
    ("Carlos López", 38, True, NEGRO, 6),
    ("CUIT/CUIL: 20-30123456-7", 26, False, GRIS, 4),
    ("Mercado Pago", 26, False, GRIS, 4),
    ("De", 28, False, GRIS, 50),
    ("Martín García", 38, True, NEGRO, 6),
    ("CUIT/CUIL: 20-28456789-3", 26, False, GRIS, 4),
    ("Número de operación de Mercado Pago", 26, False, GRIS, 60),
    ("98765432101", 34, True, NEGRO, 6),
    ("Medio de pago: Dinero en cuenta", 26, False, GRIS, 40),
])
mp.save(os.path.join(SALIDA, "mercadopago.png"))

# 2) Personal Pay: monto partido en líneas ("$" / "60.000" / "00") y etiqueta pegada al nombre
pp = pantalla([
    ("Comprobante de transferencia", 36, True, NEGRO, 0),
    ("Monto", 26, False, GRIS, 40),
    ("$", 40, True, NEGRO, 4),
    ("60.000", 56, True, NEGRO, 0),
    ("00", 30, False, NEGRO, 0),
    ("OrigenMartín García", 30, False, NEGRO, 50),
    ("DestinoCarlos López", 30, False, NEGRO, 14),
    ("Número de operación 5544332211", 28, False, NEGRO, 40),
    ("Fecha 15/09/2026 10:05", 26, False, GRIS, 30),
], fondo=(245, 248, 255))
pp.save(os.path.join(SALIDA, "personalpay.png"))

# 3) Banco / home banking: "Importe", "Origen:", "Destino:", "Código de identificación"
bn = pantalla([
    ("Transferencia realizada", 38, True, AZUL, 0),
    ("Importe: $ 120.000,00", 34, True, NEGRO, 40),
    ("Origen: Laura Sánchez", 30, False, NEGRO, 30),
    ("Destino: Proveedor Insumos Dentales SA", 30, False, NEGRO, 12),
    ("Código de identificación: AB12CD34EF", 28, False, NEGRO, 30),
    ("Fecha: 18/09/2026", 26, False, GRIS, 24),
])
bn.save(os.path.join(SALIDA, "banco.png"))

# 4) Foto mala: el comprobante de Mercado Pago inclinado, con desenfoque leve y ruido (como una foto de pantalla)
random.seed(7)
foto = mp.rotate(3.2, expand=True, fillcolor=(200, 200, 200), resample=Image.BICUBIC).filter(ImageFilter.GaussianBlur(0.9))
px = foto.load()
for _ in range(int(foto.width * foto.height * 0.02)):
    x, y = random.randrange(foto.width), random.randrange(foto.height)
    v = random.randint(-35, 35)
    r, g, b = px[x, y]
    px[x, y] = (max(0, min(255, r + v)), max(0, min(255, g + v)), max(0, min(255, b + v)))
foto.save(os.path.join(SALIDA, "foto_inclinada.jpg"), quality=62)

# 5) Imagen que NO es un comprobante (una foto cualquiera, sin texto): no debe inventar un monto
sin = Image.new("RGB", (720, 900), (180, 210, 190))
d = ImageDraw.Draw(sin)
for i in range(0, 720, 40):
    d.ellipse((i, 200 + (i % 120), i + 90, 290 + (i % 120)), fill=(120 + i % 100, 160, 140))
sin.save(os.path.join(SALIDA, "no_es_comprobante.png"))
print("fixtures en", SALIDA, os.listdir(SALIDA))
