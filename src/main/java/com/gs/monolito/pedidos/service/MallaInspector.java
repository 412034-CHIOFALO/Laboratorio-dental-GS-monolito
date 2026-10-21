package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

/**
 * Revisa por dentro un escaneo 3D (STL, OBJ o PLY) en una sola pasada y sin cargarlo entero en memoria: que sea de
 * verdad lo que dice ser, que no esté cortado, y qué mide.
 *
 * <p>Antes solo se miraba la extensión y que no fuera un ejecutable: un STL truncado a la mitad, un archivo de texto
 * renombrado o una malla con coordenadas inválidas se guardaban igual, y recién fallaban cuando el técnico intentaba
 * abrirlos. Ahora se rechazan al subirse, con un mensaje que dice qué está mal.</p>
 *
 * <p>Lo que <b>bloquea</b> (archivo corrupto, cortado o que no es lo que dice): encabezado inválido, tamaño que no
 * coincide con los triángulos declarados, líneas ilegibles, coordenadas NaN o infinitas, caras que apuntan a vértices
 * que no existen, cero triángulos en un STL, o una malla más pesada que el tope.</p>
 *
 * <p>Lo que <b>solo avisa</b> (el archivo sirve, pero algo huele raro): medidas que no parecen milímetros de una pieza
 * dental, triángulos sin superficie, bytes de más al final, un PLY u OBJ sin caras.</p>
 *
 * <p>Además calcula el SHA-256 del archivo completo, para detectar que el mismo escaneo se suba dos veces.</p>
 */
@Component
public class MallaInspector {

    public static final String STL_BINARIO = "STL_BINARIO";
    public static final String STL_ASCII = "STL_ASCII";
    public static final String OBJ = "OBJ";
    public static final String PLY_ASCII = "PLY_ASCII";
    public static final String PLY_BINARIO = "PLY_BINARIO";

    public static final long MAX_TRIANGULOS_DEFECTO = 25_000_000L;
    private static final int MAX_LINEA = 20_000;
    private static final int MAX_ENCABEZADO_PLY = 64 * 1024;
    private static final long MAX_ELEMENTOS_PLY = 200_000_000L;
    private static final int MAX_VERTICES_POR_CARA = 100_000;
    /** Bytes de más tolerados al final de un STL binario antes de sospechar que no es un STL. */
    private static final long COLA_TOLERADA_STL = 4096;

    private final long maxTriangulos;

    public MallaInspector(@Value("${gs.escaneos.max-triangulos:25000000}") long maxTriangulos) {
        this.maxTriangulos = maxTriangulos;
    }

    /** El escaneo no es válido: el mensaje es lo que ve quien lo subió. */
    public static class MallaInvalidaException extends BusinessException {
        public MallaInvalidaException(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * @param triangulos cantidad de triángulos (las caras de más de tres lados cuentan como varios)
     * @param vertices   vértices declarados; null en STL, que repite los vértices en cada triángulo
     * @param dimX       medida en X, en las unidades del archivo (los escáneres dentales exportan milímetros)
     */
    public record InformeMalla(String formato, long triangulos, Long vertices,
                               BigDecimal dimX, BigDecimal dimY, BigDecimal dimZ,
                               String sha256, List<String> advertencias) {}

    // ── Punto de entrada ─────────────────────────────────────────────

    /**
     * @param tamanio   bytes del archivo (para detectar un STL binario cortado)
     * @param extension stl, obj o ply (sin punto)
     * @throws MallaInvalidaException si el archivo no sirve
     */
    public InformeMalla inspeccionar(InputStream entrada, long tamanio, String extension) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        List<String> adv = new ArrayList<>();
        Medidas medidas = new Medidas();
        Lectura lectura;
        try (Fuente f = new Fuente(new DigestInputStream(entrada, sha))) {
            try {
                lectura = switch (ext) {
                    case "stl" -> leerStl(f, tamanio, medidas, adv);
                    case "obj" -> leerObj(f, medidas, adv);
                    case "ply" -> leerPly(f, medidas, adv);
                    default -> throw new MallaInvalidaException("Formato no soportado: " + ext + ". Se aceptan STL, OBJ y PLY.");
                };
            } catch (EOFException e) {
                throw new MallaInvalidaException("El archivo está cortado: termina antes de lo que declara su propio encabezado. Volvé a exportarlo desde el escáner.");
            }
            // El hash cubre el archivo entero: lo que quede después de la malla también se lee.
            long sobrante = f.agotar();
            if (sobrante > 0 && lectura.avisarSobrante) {
                adv.add("El archivo tiene " + sobrante + " bytes de más al final (se ignoran).");
            }
        }
        if (!medidas.hayDatos()) {
            throw new MallaInvalidaException("El escaneo no tiene vértices: está vacío.");
        }
        advertirMedidas(medidas, adv);
        return new InformeMalla(lectura.formato, lectura.triangulos, lectura.vertices,
                medidas.dim(0), medidas.dim(1), medidas.dim(2),
                HexFormat.of().formatHex(sha.digest()), List.copyOf(adv));
    }

    private record Lectura(String formato, long triangulos, Long vertices, boolean avisarSobrante) {}

    // ── STL ──────────────────────────────────────────────────────────

    private Lectura leerStl(Fuente f, long tamanio, Medidas m, List<String> adv) throws IOException {
        byte[] cab = f.espiar(84);
        if (cab.length < 84) {
            throw new MallaInvalidaException("El archivo es demasiado chico para ser un STL (" + cab.length + " bytes).");
        }
        long declarados = leU32(cab, 80);
        long esperado = 84 + 50 * declarados;
        boolean empiezaSolid = empiezaCon(cab, "solid");
        // Un STL binario tiene bytes de control en el encabezado o en el contador (con menos de 16 millones de
        // triángulos, el byte alto del contador es 0). Un archivo de texto no: sus bytes 80-83 son letras, y leídos
        // como contador darían una cantidad absurda de triángulos que haría pasar al archivo por "cortado".
        boolean pareceBinario = declarados > 0 && declarados <= maxTriangulos && hayBytesDeControl(cab);

        if (declarados > 0 && tamanio == esperado) {
            return leerStlBinario(f, declarados, m, adv);
        }
        if (empiezaSolid) {
            try {
                return leerStlAscii(f, m, adv);
            } catch (MallaInvalidaException e) {
                // Un STL binario cuyo encabezado también dice "solid" y quedó cortado cae acá: se explica.
                if (pareceBinario && tamanio < esperado) {
                    throw new MallaInvalidaException(mensajeCortado(declarados, esperado, tamanio));
                }
                throw e;
            }
        }
        if (pareceBinario && tamanio > esperado && tamanio - esperado <= COLA_TOLERADA_STL) {
            return leerStlBinario(f, declarados, m, adv);   // un STL con unos bytes de relleno al final
        }
        if (pareceBinario && tamanio < esperado) {
            throw new MallaInvalidaException(mensajeCortado(declarados, esperado, tamanio));
        }
        throw new MallaInvalidaException("No parece un STL: no es ASCII (no empieza con 'solid') y su tamaño no coincide con el de un STL binario.");
    }

    private static boolean hayBytesDeControl(byte[] b) {
        for (byte x : b) {
            if ((x & 0xFF) < 9) return true;
        }
        return false;
    }

    private static String mensajeCortado(long declarados, long esperado, long tamanio) {
        return "El STL está cortado o corrupto: declara " + declarados + " triángulos (" + esperado
                + " bytes) pero el archivo mide " + tamanio + " bytes. Volvé a exportarlo desde el escáner.";
    }

    private Lectura leerStlBinario(Fuente f, long n, Medidas m, List<String> adv) throws IOException {
        if (n > maxTriangulos) {
            throw new MallaInvalidaException("La malla tiene " + n + " triángulos y el máximo permitido es " + maxTriangulos
                    + ". Simplificala (decimar) antes de subirla.");
        }
        f.saltar(84);
        byte[] bloque = new byte[50 * 4096];
        ByteBuffer bb = ByteBuffer.wrap(bloque).order(ByteOrder.LITTLE_ENDIAN);
        long invalidas = 0;
        long degenerados = 0;
        long restantes = n;
        while (restantes > 0) {
            int tri = (int) Math.min(restantes, 4096);
            f.exacto(bloque, tri * 50);
            for (int i = 0; i < tri; i++) {
                int o = i * 50 + 12;   // se saltea la normal
                double ax = bb.getFloat(o), ay = bb.getFloat(o + 4), az = bb.getFloat(o + 8);
                double bx = bb.getFloat(o + 12), by = bb.getFloat(o + 16), bz = bb.getFloat(o + 20);
                double cx = bb.getFloat(o + 24), cy = bb.getFloat(o + 28), cz = bb.getFloat(o + 32);
                if (!finito(ax, ay, az) || !finito(bx, by, bz) || !finito(cx, cy, cz)) {
                    invalidas++;
                    continue;
                }
                m.agregar(ax, ay, az);
                m.agregar(bx, by, bz);
                m.agregar(cx, cy, cz);
                double ux = bx - ax, uy = by - ay, uz = bz - az;
                double vx = cx - ax, vy = cy - ay, vz = cz - az;
                double px = uy * vz - uz * vy, py = uz * vx - ux * vz, pz = ux * vy - uy * vx;
                if (px * px + py * py + pz * pz <= 1e-18) degenerados++;
            }
            restantes -= tri;
        }
        if (invalidas > 0) {
            throw new MallaInvalidaException("El STL tiene " + invalidas + " triángulos con coordenadas inválidas (NaN o infinito): el archivo está corrupto.");
        }
        if (degenerados > 0) {
            adv.add(degenerados + " de " + n + " triángulos no tienen superficie (son degenerados).");
        }
        return new Lectura(STL_BINARIO, n, null, true);
    }

    private Lectura leerStlAscii(Fuente f, Medidas m, List<String> adv) throws IOException {
        long facetas = 0;
        long vertices = 0;
        boolean cerrado = false;
        boolean empezo = false;
        String linea;
        while ((linea = f.linea(MAX_LINEA)) != null) {
            String t = linea.strip();
            if (t.isEmpty()) continue;
            if (!empezo) {
                if (!empiezaConIgnorandoCaja(t, "solid")) {
                    throw new MallaInvalidaException("No es un STL ASCII: la primera línea no empieza con 'solid'.");
                }
                empezo = true;
                continue;
            }
            if (empiezaConIgnorandoCaja(t, "facet")) {
                facetas++;
                if (facetas > maxTriangulos) {
                    throw new MallaInvalidaException("La malla supera los " + maxTriangulos + " triángulos permitidos. Simplificala antes de subirla.");
                }
            } else if (empiezaConIgnorandoCaja(t, "vertex")) {
                double[] v = numeros(t, 1, 3, f.numeroLinea(), "STL");
                m.agregar(v[0], v[1], v[2]);
                vertices++;
            } else if (empiezaConIgnorandoCaja(t, "endsolid")) {
                cerrado = true;
            }
        }
        if (facetas == 0) {
            throw new MallaInvalidaException("El STL no tiene triángulos.");
        }
        if (vertices != 3 * facetas) {
            throw new MallaInvalidaException("El STL ASCII está incompleto: tiene " + facetas + " triángulos pero " + vertices
                    + " vértices (debería haber " + (3 * facetas) + "). Probablemente está cortado.");
        }
        if (!cerrado) adv.add("El STL no termina con 'endsolid': puede estar cortado, aunque los triángulos están completos.");
        return new Lectura(STL_ASCII, facetas, null, false);
    }

    // ── OBJ ──────────────────────────────────────────────────────────

    private Lectura leerObj(Fuente f, Medidas m, List<String> adv) throws IOException {
        byte[] inicio = f.espiar(4096);
        for (byte b : inicio) {
            if (b == 0) throw new MallaInvalidaException("No es un OBJ: es un archivo binario (un OBJ es texto).");
        }
        long vertices = 0;
        long triangulos = 0;
        long indiceMayor = 0;
        String linea;
        while ((linea = f.linea(MAX_LINEA)) != null) {
            String t = linea.strip();
            if (t.length() < 2) continue;
            if (t.startsWith("v ") || t.startsWith("v\t")) {
                double[] v = numeros(t, 1, 3, f.numeroLinea(), "OBJ");
                m.agregar(v[0], v[1], v[2]);
                vertices++;
            } else if (t.startsWith("f ") || t.startsWith("f\t")) {
                StringTokenizer st = new StringTokenizer(t.substring(1), " \t");
                int n = 0;
                while (st.hasMoreTokens()) {
                    String ficha = st.nextToken();
                    int barra = ficha.indexOf('/');
                    String numero = barra >= 0 ? ficha.substring(0, barra) : ficha;
                    long idx;
                    try {
                        idx = Long.parseLong(numero);
                    } catch (NumberFormatException e) {
                        throw new MallaInvalidaException("OBJ: la cara de la línea " + f.numeroLinea() + " tiene un índice ilegible (\"" + ficha + "\").");
                    }
                    if (idx < 0) idx = vertices + idx + 1;   // índice relativo al final
                    if (idx < 1) {
                        throw new MallaInvalidaException("OBJ: la cara de la línea " + f.numeroLinea() + " apunta a un vértice que no existe.");
                    }
                    indiceMayor = Math.max(indiceMayor, idx);
                    if (++n > MAX_VERTICES_POR_CARA) {
                        throw new MallaInvalidaException("OBJ: la cara de la línea " + f.numeroLinea() + " tiene demasiados vértices.");
                    }
                }
                if (n < 3) {
                    throw new MallaInvalidaException("OBJ: la cara de la línea " + f.numeroLinea() + " tiene menos de 3 vértices.");
                }
                triangulos += n - 2;
                if (triangulos > maxTriangulos) {
                    throw new MallaInvalidaException("La malla supera los " + maxTriangulos + " triángulos permitidos. Simplificala antes de subirla.");
                }
            }
        }
        if (vertices < 3) {
            throw new MallaInvalidaException("No parece un OBJ válido: tiene " + vertices + " vértices (líneas 'v x y z').");
        }
        if (indiceMayor > vertices) {
            throw new MallaInvalidaException("OBJ corrupto: hay caras que apuntan al vértice " + indiceMayor + " pero el archivo solo tiene " + vertices + ".");
        }
        if (triangulos == 0) adv.add("El OBJ no tiene caras: es una nube de puntos, no una malla.");
        return new Lectura(OBJ, triangulos, vertices, false);
    }

    // ── PLY ──────────────────────────────────────────────────────────

    private record Propiedad(String nombre, String tipo, String tipoCantidad) {
        boolean esLista() {
            return tipoCantidad != null;
        }
    }

    private static final class Elemento {
        final String nombre;
        final long cantidad;
        final List<Propiedad> propiedades = new ArrayList<>();

        Elemento(String nombre, long cantidad) {
            this.nombre = nombre;
            this.cantidad = cantidad;
        }
    }

    private Lectura leerPly(Fuente f, Medidas m, List<String> adv) throws IOException {
        String primera = f.linea(MAX_LINEA);
        if (primera == null || !primera.strip().equalsIgnoreCase("ply")) {
            throw new MallaInvalidaException("No es un PLY: no empieza con 'ply'.");
        }
        String formato = null;
        List<Elemento> elementos = new ArrayList<>();
        Elemento actual = null;
        int bytesEncabezado = primera.length() + 1;
        boolean fin = false;
        while (!fin) {
            String l = f.linea(MAX_LINEA);
            if (l == null) throw new MallaInvalidaException("El encabezado del PLY está cortado: no tiene 'end_header'.");
            bytesEncabezado += l.length() + 1;
            if (bytesEncabezado > MAX_ENCABEZADO_PLY) throw new MallaInvalidaException("El encabezado del PLY es demasiado largo: el archivo no parece válido.");
            String[] t = l.strip().split("\\s+");
            switch (t[0]) {
                case "format" -> formato = t.length > 1 ? t[1] : null;
                case "element" -> {
                    if (t.length < 3) throw new MallaInvalidaException("PLY: línea 'element' mal formada: \"" + l.strip() + "\".");
                    actual = new Elemento(t[1], parseConteo(t[2], t[1]));
                    elementos.add(actual);
                }
                case "property" -> {
                    if (actual == null) throw new MallaInvalidaException("PLY: hay una 'property' antes de cualquier 'element'.");
                    if (t.length >= 5 && t[1].equals("list")) actual.propiedades.add(new Propiedad(t[4], t[3], t[2]));
                    else if (t.length >= 3) actual.propiedades.add(new Propiedad(t[2], t[1], null));
                    else throw new MallaInvalidaException("PLY: línea 'property' mal formada: \"" + l.strip() + "\".");
                }
                case "end_header" -> fin = true;
                default -> { /* comment, obj_info, líneas vacías */ }
            }
        }
        if (formato == null || !(formato.equals("ascii") || formato.equals("binary_little_endian") || formato.equals("binary_big_endian"))) {
            throw new MallaInvalidaException("PLY: formato no soportado (" + formato + "). Se aceptan ascii, binary_little_endian y binary_big_endian.");
        }
        Elemento verticesElem = elementos.stream().filter(e -> e.nombre.equals("vertex")).findFirst()
                .orElseThrow(() -> new MallaInvalidaException("PLY: no tiene el elemento 'vertex'."));
        for (String eje : new String[]{"x", "y", "z"}) {
            if (verticesElem.propiedades.stream().noneMatch(p -> p.nombre().equals(eje) && !p.esLista())) {
                throw new MallaInvalidaException("PLY: los vértices no tienen la coordenada '" + eje + "'.");
            }
        }
        boolean ascii = formato.equals("ascii");
        ByteOrder orden = formato.equals("binary_big_endian") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        long cantidadVertices = verticesElem.cantidad;
        long triangulos = 0;
        for (Elemento e : elementos) {
            boolean esVertice = e == verticesElem;
            boolean esCara = e.nombre.equals("face");
            for (long i = 0; i < e.cantidad; i++) {
                if (ascii) triangulos += leerItemAscii(f, e, esVertice, esCara, cantidadVertices, m);
                else triangulos += leerItemBinario(f, e, esVertice, esCara, cantidadVertices, orden, m);
                if (triangulos > maxTriangulos) {
                    throw new MallaInvalidaException("La malla supera los " + maxTriangulos + " triángulos permitidos. Simplificala antes de subirla.");
                }
            }
        }
        if (triangulos == 0) adv.add("El PLY no tiene caras: es una nube de puntos, no una malla.");
        return new Lectura(ascii ? PLY_ASCII : PLY_BINARIO, triangulos, cantidadVertices, false);
    }

    private static long parseConteo(String texto, String elemento) {
        try {
            long n = Long.parseLong(texto);
            if (n < 0 || n > MAX_ELEMENTOS_PLY) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new MallaInvalidaException("PLY: la cantidad de '" + elemento + "' (" + texto + ") no es válida.");
        }
    }

    /** Lee una línea de un elemento PLY ASCII. Devuelve los triángulos que aporta (solo las caras). */
    private long leerItemAscii(Fuente f, Elemento e, boolean esVertice, boolean esCara, long vertices, Medidas m) throws IOException {
        String linea = f.linea(MAX_LINEA);
        if (linea == null) throw new EOFException();
        StringTokenizer st = new StringTokenizer(linea, " \t");
        long triangulos = 0;
        double[] xyz = esVertice ? new double[3] : null;
        boolean primeraLista = true;
        for (Propiedad p : e.propiedades) {
            if (!st.hasMoreTokens()) throw new MallaInvalidaException("PLY: la línea " + f.numeroLinea() + " tiene menos valores de los que declara el encabezado.");
            if (p.esLista()) {
                int n = (int) entero(st.nextToken(), f);
                if (esCara && primeraLista) {
                    if (n < 3 || n > MAX_VERTICES_POR_CARA) throw new MallaInvalidaException("PLY: la cara de la línea " + f.numeroLinea() + " tiene " + n + " vértices.");
                    for (int k = 0; k < n; k++) {
                        if (!st.hasMoreTokens()) throw new MallaInvalidaException("PLY: la cara de la línea " + f.numeroLinea() + " está incompleta.");
                        verificarIndice(entero(st.nextToken(), f), vertices);
                    }
                    triangulos += n - 2;
                } else {
                    for (int k = 0; k < n && st.hasMoreTokens(); k++) st.nextToken();
                }
                primeraLista = false;
            } else {
                String valor = st.nextToken();
                if (esVertice) {
                    switch (p.nombre()) {
                        case "x" -> xyz[0] = decimal(valor, f);
                        case "y" -> xyz[1] = decimal(valor, f);
                        case "z" -> xyz[2] = decimal(valor, f);
                        default -> { }
                    }
                }
            }
        }
        if (esVertice) {
            if (!finito(xyz[0], xyz[1], xyz[2])) throw new MallaInvalidaException("PLY: el vértice de la línea " + f.numeroLinea() + " tiene coordenadas inválidas (NaN o infinito).");
            m.agregar(xyz[0], xyz[1], xyz[2]);
        }
        return triangulos;
    }

    /** Lee un registro de un elemento PLY binario. Devuelve los triángulos que aporta (solo las caras). */
    private long leerItemBinario(Fuente f, Elemento e, boolean esVertice, boolean esCara, long vertices, ByteOrder orden, Medidas m) throws IOException {
        long triangulos = 0;
        double[] xyz = esVertice ? new double[3] : null;
        boolean primeraLista = true;
        f.esc.order(orden);
        for (Propiedad p : e.propiedades) {
            if (p.esLista()) {
                long n = leerNumero(f, p.tipoCantidad());
                int tam = bytesDe(p.tipo());
                if (esCara && primeraLista) {
                    if (n < 3 || n > MAX_VERTICES_POR_CARA) throw new MallaInvalidaException("PLY: una cara tiene " + n + " vértices.");
                    for (long k = 0; k < n; k++) verificarIndice(leerNumero(f, p.tipo()), vertices);
                    triangulos += n - 2;
                } else {
                    f.saltar(n * tam);
                }
                primeraLista = false;
            } else if (esVertice && (p.nombre().equals("x") || p.nombre().equals("y") || p.nombre().equals("z"))) {
                xyz[p.nombre().charAt(0) - 'x'] = leerDecimal(f, p.tipo());
            } else {
                f.saltar(bytesDe(p.tipo()));
            }
        }
        if (esVertice) {
            if (!finito(xyz[0], xyz[1], xyz[2])) throw new MallaInvalidaException("PLY: hay un vértice con coordenadas inválidas (NaN o infinito).");
            m.agregar(xyz[0], xyz[1], xyz[2]);
        }
        return triangulos;
    }

    private static int bytesDe(String tipo) {
        return switch (tipo) {
            case "char", "int8", "uchar", "uint8" -> 1;
            case "short", "int16", "ushort", "uint16" -> 2;
            case "int", "int32", "uint", "uint32", "float", "float32" -> 4;
            case "double", "float64" -> 8;
            default -> throw new MallaInvalidaException("PLY: tipo de dato desconocido (" + tipo + ").");
        };
    }

    private static long leerNumero(Fuente f, String tipo) throws IOException {
        ByteBuffer e = f.esc;
        f.exacto(e.array(), bytesDe(tipo));
        return switch (tipo) {
            case "char", "int8" -> e.get(0);
            case "uchar", "uint8" -> e.get(0) & 0xFFL;
            case "short", "int16" -> e.getShort(0);
            case "ushort", "uint16" -> e.getShort(0) & 0xFFFFL;
            case "int", "int32" -> e.getInt(0);
            case "uint", "uint32" -> e.getInt(0) & 0xFFFFFFFFL;
            case "float", "float32" -> (long) e.getFloat(0);
            default -> (long) e.getDouble(0);
        };
    }

    private static double leerDecimal(Fuente f, String tipo) throws IOException {
        ByteBuffer e = f.esc;
        f.exacto(e.array(), bytesDe(tipo));
        return switch (tipo) {
            case "float", "float32" -> e.getFloat(0);
            case "double", "float64" -> e.getDouble(0);
            case "char", "int8" -> e.get(0);
            case "uchar", "uint8" -> e.get(0) & 0xFF;
            case "short", "int16" -> e.getShort(0);
            case "ushort", "uint16" -> e.getShort(0) & 0xFFFF;
            case "int", "int32" -> e.getInt(0);
            default -> e.getInt(0) & 0xFFFFFFFFL;
        };
    }

    private static void verificarIndice(long indice, long vertices) {
        if (indice < 0 || indice >= vertices) {
            throw new MallaInvalidaException("PLY corrupto: hay una cara que apunta al vértice " + indice + " pero el archivo tiene " + vertices + " vértices.");
        }
    }

    private static long entero(String texto, Fuente f) {
        try {
            return Long.parseLong(texto);
        } catch (NumberFormatException e) {
            throw new MallaInvalidaException("PLY: número ilegible en la línea " + f.numeroLinea() + " (\"" + texto + "\").");
        }
    }

    private static double decimal(String texto, Fuente f) {
        try {
            return Double.parseDouble(texto);
        } catch (NumberFormatException e) {
            throw new MallaInvalidaException("PLY: número ilegible en la línea " + f.numeroLinea() + " (\"" + texto + "\").");
        }
    }

    // ── Utilidades ───────────────────────────────────────────────────

    /** Lee {@code cuantos} números a partir del token {@code desde} de una línea ("vertex 1 2 3", "v 1 2 3"). */
    private static double[] numeros(String linea, int desde, int cuantos, long numeroLinea, String formato) {
        StringTokenizer st = new StringTokenizer(linea, " \t");
        for (int i = 0; i < desde && st.hasMoreTokens(); i++) st.nextToken();
        double[] r = new double[cuantos];
        for (int i = 0; i < cuantos; i++) {
            if (!st.hasMoreTokens()) {
                throw new MallaInvalidaException(formato + ": la línea " + numeroLinea + " tiene menos de " + cuantos + " coordenadas.");
            }
            String token = st.nextToken();
            try {
                r[i] = Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw new MallaInvalidaException(formato + ": número ilegible en la línea " + numeroLinea + " (\"" + token + "\").");
            }
            if (!Double.isFinite(r[i])) {
                throw new MallaInvalidaException(formato + ": coordenada inválida (NaN o infinito) en la línea " + numeroLinea + ".");
            }
        }
        return r;
    }

    private static boolean finito(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private static long leU32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    private static boolean empiezaCon(byte[] b, String texto) {
        int i = 0;
        while (i < b.length && (b[i] == ' ' || b[i] == '\t' || b[i] == '\r' || b[i] == '\n')) i++;
        if (i + texto.length() > b.length) return false;
        for (int k = 0; k < texto.length(); k++) {
            if (Character.toLowerCase((char) b[i + k]) != texto.charAt(k)) return false;
        }
        return true;
    }

    private static boolean empiezaConIgnorandoCaja(String t, String prefijo) {
        return t.regionMatches(true, 0, prefijo, 0, prefijo.length());
    }

    /** Las medidas deben parecer milímetros de una pieza dental; si no, se avisa (no se bloquea). */
    private static void advertirMedidas(Medidas m, List<String> adv) {
        double mayor = Math.max(m.ancho(0), Math.max(m.ancho(1), m.ancho(2)));
        String medidas = String.format(Locale.ROOT, "%.1f × %.1f × %.1f", m.ancho(0), m.ancho(1), m.ancho(2));
        if (mayor < 1) {
            adv.add("Las medidas (" + medidas + ") son menores a 1: ¿el archivo está en metros en vez de milímetros?");
        } else if (mayor < 5) {
            adv.add("Las medidas (" + medidas + ") son muy chicas para una pieza dental (se esperan milímetros).");
        } else if (mayor > 400) {
            adv.add("Las medidas (" + medidas + ") son muy grandes para una pieza dental: ¿está en micrones o incluye algo más que el modelo?");
        }
    }

    /** Caja que envuelve a todos los vértices. */
    private static final class Medidas {
        private final double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        private final double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        private boolean datos;

        void agregar(double x, double y, double z) {
            datos = true;
            if (x < min[0]) min[0] = x;
            if (y < min[1]) min[1] = y;
            if (z < min[2]) min[2] = z;
            if (x > max[0]) max[0] = x;
            if (y > max[1]) max[1] = y;
            if (z > max[2]) max[2] = z;
        }

        boolean hayDatos() {
            return datos;
        }

        double ancho(int eje) {
            return max[eje] - min[eje];
        }

        BigDecimal dim(int eje) {
            return BigDecimal.valueOf(ancho(eje)).setScale(3, RoundingMode.HALF_UP);
        }
    }

    /**
     * Lector con buffer propio: líneas de texto con tope de largo (un archivo sin saltos de línea no puede agotar la
     * memoria), lecturas binarias exactas, y {@code espiar} para decidir el formato sin consumir.
     */
    private static final class Fuente implements AutoCloseable {
        private final InputStream in;
        private final byte[] buf = new byte[256 * 1024];
        /** Buffer de trabajo para leer un número binario; es de esta inspección, no se comparte entre subidas. */
        final ByteBuffer esc = ByteBuffer.allocate(8);
        private int pos;
        private int lim;
        private long lineas;
        private final ByteArrayBuilder linea = new ByteArrayBuilder();

        Fuente(InputStream in) {
            this.in = in;
        }

        private boolean llenar() throws IOException {
            if (pos < lim) return true;
            int n = in.read(buf, 0, buf.length);
            if (n <= 0) return false;
            pos = 0;
            lim = n;
            return true;
        }

        /** Hasta {@code n} bytes desde la posición actual, sin consumirlos. */
        byte[] espiar(int n) throws IOException {
            if (n > buf.length) throw new IllegalArgumentException();
            while (lim - pos < n) {
                if (pos > 0) {
                    System.arraycopy(buf, pos, buf, 0, lim - pos);
                    lim -= pos;
                    pos = 0;
                }
                int leido = in.read(buf, lim, buf.length - lim);
                if (leido <= 0) break;
                lim += leido;
            }
            return Arrays.copyOfRange(buf, pos, pos + Math.min(n, lim - pos));
        }

        void exacto(byte[] destino, int cuantos) throws IOException {
            int hecho = 0;
            while (hecho < cuantos) {
                if (!llenar()) throw new EOFException();
                int n = Math.min(cuantos - hecho, lim - pos);
                System.arraycopy(buf, pos, destino, hecho, n);
                pos += n;
                hecho += n;
            }
        }

        void saltar(long cuantos) throws IOException {
            long resta = cuantos;
            while (resta > 0) {
                if (!llenar()) throw new EOFException();
                int n = (int) Math.min(resta, lim - pos);
                pos += n;
                resta -= n;
            }
        }

        /** La próxima línea (sin el salto), o null si no quedan bytes. */
        String linea(int max) throws IOException {
            linea.limpiar();
            boolean algo = false;
            while (true) {
                if (!llenar()) break;
                algo = true;
                int ini = pos;
                while (pos < lim && buf[pos] != '\n') pos++;
                linea.agregar(buf, ini, pos - ini);
                if (linea.largo() > max) {
                    throw new MallaInvalidaException("El archivo tiene una línea de más de " + max + " caracteres: no es un archivo de texto válido.");
                }
                if (pos < lim) {   // encontró el salto
                    pos++;
                    lineas++;
                    return linea.texto();
                }
            }
            if (!algo || linea.largo() == 0) return null;
            lineas++;
            return linea.texto();
        }

        long numeroLinea() {
            return lineas;
        }

        /** Lee y descarta el resto del archivo (para que el hash lo cubra entero). Devuelve cuántos bytes eran. */
        long agotar() throws IOException {
            long total = lim - pos;
            pos = lim;
            while (true) {
                int n = in.read(buf, 0, buf.length);
                if (n <= 0) break;
                total += n;
            }
            return total;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Acumula los bytes de una línea (sin el '\r' final) y los convierte a texto. */
    private static final class ByteArrayBuilder {
        private byte[] datos = new byte[256];
        private int n;

        void limpiar() {
            n = 0;
        }

        void agregar(byte[] origen, int desde, int cuantos) {
            if (n + cuantos > datos.length) datos = Arrays.copyOf(datos, Math.max(datos.length * 2, n + cuantos));
            System.arraycopy(origen, desde, datos, n, cuantos);
            n += cuantos;
        }

        int largo() {
            return n;
        }

        String texto() {
            int fin = n;
            if (fin > 0 && datos[fin - 1] == '\r') fin--;
            return new String(datos, 0, fin, StandardCharsets.ISO_8859_1);
        }
    }
}
