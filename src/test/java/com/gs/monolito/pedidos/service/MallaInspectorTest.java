package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.service.MallaInspector.InformeMalla;
import com.gs.monolito.pedidos.service.MallaInspector.MallaInvalidaException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Qué se acepta y qué se rechaza de un escaneo 3D. Todos los archivos se arman acá (un cubo de 10 × 20 × 30 mm
 * en cada formato) para probar tanto lo válido como cada forma de estar roto.
 */
class MallaInspectorTest {

    private final MallaInspector inspector = new MallaInspector(MallaInspector.MAX_TRIANGULOS_DEFECTO);

    // ── Un cubo de 10 × 20 × 30 ──────────────────────────────────────

    private static double[][] vertices(double k) {
        double x = 10 * k, y = 20 * k, z = 30 * k;
        return new double[][]{{0, 0, 0}, {x, 0, 0}, {x, y, 0}, {0, y, 0}, {0, 0, z}, {x, 0, z}, {x, y, z}, {0, y, z}};
    }

    private static final int[][] CARAS = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};

    private static int[][] triangulos() {
        return Arrays.stream(CARAS).flatMap(c -> Arrays.stream(new int[][]{{c[0], c[1], c[2]}, {c[0], c[2], c[3]}})).toArray(int[][]::new);
    }

    private static byte[] stlBinario(double k, String encabezado) {
        int[][] tris = triangulos();
        ByteBuffer bb = ByteBuffer.allocate(84 + 50 * tris.length).order(ByteOrder.LITTLE_ENDIAN);
        byte[] cab = Arrays.copyOf(encabezado.getBytes(StandardCharsets.US_ASCII), 80);
        bb.put(cab).putInt(tris.length);
        double[][] v = vertices(k);
        for (int[] t : tris) {
            bb.putFloat(0).putFloat(0).putFloat(0);
            for (int i : t) bb.putFloat((float) v[i][0]).putFloat((float) v[i][1]).putFloat((float) v[i][2]);
            bb.putShort((short) 0);
        }
        return bb.array();
    }

    private static byte[] stlAscii(boolean cerrar) {
        StringBuilder sb = new StringBuilder("solid cubo\n");
        double[][] v = vertices(1);
        for (int[] t : triangulos()) {
            sb.append("  facet normal 0 0 0\n    outer loop\n");
            for (int i : t) sb.append(String.format(java.util.Locale.ROOT, "      vertex %f %f %f\n", v[i][0], v[i][1], v[i][2]));
            sb.append("    endloop\n  endfacet\n");
        }
        if (cerrar) sb.append("endsolid cubo\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] obj(String fin) {
        StringBuilder sb = new StringBuilder("# cubo\n");
        for (double[] v : vertices(1)) sb.append(String.format(java.util.Locale.ROOT, "v %f %f %f\n", v[0], v[1], v[2]));
        sb.append("vt 0 0\nvn 0 0 1\n");
        for (int[] c : CARAS) sb.append("f ").append(Arrays.stream(c).mapToObj(i -> (i + 1) + "/1/1").collect(Collectors.joining(" "))).append("\n");
        return (sb + fin).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] plyAscii() {
        StringBuilder sb = new StringBuilder("ply\nformat ascii 1.0\ncomment cubo\nelement vertex 8\nproperty float x\nproperty float y\nproperty float z\n"
                + "property uchar red\nproperty uchar green\nproperty uchar blue\nelement face 6\nproperty list uchar int vertex_indices\nend_header\n");
        for (double[] v : vertices(1)) sb.append(String.format(java.util.Locale.ROOT, "%f %f %f 200 100 50\n", v[0], v[1], v[2]));
        for (int[] c : CARAS) sb.append("4 ").append(Arrays.stream(c).mapToObj(String::valueOf).collect(Collectors.joining(" "))).append("\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] plyBinario(ByteOrder orden, int indiceFuera) {
        String cab = "ply\nformat " + (orden == ByteOrder.LITTLE_ENDIAN ? "binary_little_endian" : "binary_big_endian") + " 1.0\n"
                + "element vertex 8\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\n"
                + "element face 6\nproperty list uchar int vertex_indices\nend_header\n";
        ByteBuffer bb = ByteBuffer.allocate(cab.length() + 8 * 15 + 6 * (1 + 16)).order(orden);
        bb.put(cab.getBytes(StandardCharsets.US_ASCII));
        for (double[] v : vertices(1)) bb.putFloat((float) v[0]).putFloat((float) v[1]).putFloat((float) v[2]).put((byte) 200).put((byte) 100).put((byte) 50);
        for (int[] c : CARAS) {
            bb.put((byte) 4);
            for (int i : c) bb.putInt(i);
        }
        byte[] r = bb.array();
        if (indiceFuera >= 0) {   // pisa el último índice de la última cara con uno que no existe
            ByteBuffer.wrap(r).order(orden).putInt(r.length - 4, indiceFuera);
        }
        return r;
    }

    private InformeMalla inspeccionar(byte[] datos, String ext) throws IOException {
        return inspector.inspeccionar(new ByteArrayInputStream(datos), datos.length, ext);
    }

    private static String sha256(byte[] datos) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
    }

    private static void esCubo(InformeMalla r) {
        assertThat(r.triangulos()).isEqualTo(12);
        assertThat(r.dimX()).isEqualByComparingTo("10");
        assertThat(r.dimY()).isEqualByComparingTo("20");
        assertThat(r.dimZ()).isEqualByComparingTo("30");
        assertThat(r.advertencias()).isEmpty();
    }

    // ── Lo válido ────────────────────────────────────────────────────

    @Test
    void unStlBinarioValidoSeMideYSeHashea() throws Exception {
        byte[] datos = stlBinario(1, "binary stl de prueba");
        InformeMalla r = inspeccionar(datos, "stl");

        assertThat(r.formato()).isEqualTo(MallaInspector.STL_BINARIO);
        esCubo(r);
        assertThat(r.vertices()).isNull();
        assertThat(r.sha256()).isEqualTo(sha256(datos));
    }

    @Test
    void unStlBinarioCuyoEncabezadoDiceSolidSigueSiendoBinario() throws Exception {
        // Algunos programas (SolidWorks) escriben "solid" en el encabezado de un STL binario.
        InformeMalla r = inspeccionar(stlBinario(1, "solid exportado por un programa"), "stl");

        assertThat(r.formato()).isEqualTo(MallaInspector.STL_BINARIO);
        esCubo(r);
    }

    @Test
    void unStlAsciiValido() throws Exception {
        InformeMalla r = inspeccionar(stlAscii(true), "stl");

        assertThat(r.formato()).isEqualTo(MallaInspector.STL_ASCII);
        esCubo(r);
    }

    @Test
    void unObjConCarasDeCuatroLadosCuentaDosTriangulosPorCara() throws Exception {
        InformeMalla r = inspeccionar(obj(""), "obj");

        assertThat(r.formato()).isEqualTo(MallaInspector.OBJ);
        assertThat(r.vertices()).isEqualTo(8);
        esCubo(r);
    }

    @Test
    void unObjConIndicesNegativosSeResuelveRelativoAlFinal() throws Exception {
        String texto = "v 0 0 0\nv 10 0 0\nv 0 20 0\nv 0 0 30\nf -4 -3 -2\nf -4 -2 -1\n";

        InformeMalla r = inspeccionar(texto.getBytes(StandardCharsets.US_ASCII), "obj");

        assertThat(r.triangulos()).isEqualTo(2);
        assertThat(r.dimZ()).isEqualByComparingTo("30");
    }

    @Test
    void unPlyAsciiValido() throws Exception {
        InformeMalla r = inspeccionar(plyAscii(), "ply");

        assertThat(r.formato()).isEqualTo(MallaInspector.PLY_ASCII);
        assertThat(r.vertices()).isEqualTo(8);
        esCubo(r);
    }

    @Test
    void unPlyBinarioEnAmbosOrdenesDeBytes() throws Exception {
        for (ByteOrder orden : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            InformeMalla r = inspeccionar(plyBinario(orden, -1), "ply");

            assertThat(r.formato()).as(orden.toString()).isEqualTo(MallaInspector.PLY_BINARIO);
            assertThat(r.vertices()).isEqualTo(8);
            esCubo(r);
        }
    }

    @Test
    void laExtensionNoDistingueMayusculas() throws Exception {
        assertThat(inspeccionar(stlAscii(true), "STL").formato()).isEqualTo(MallaInspector.STL_ASCII);
    }

    // ── Lo roto ──────────────────────────────────────────────────────

    private void rechaza(byte[] datos, String ext, String mensaje) {
        assertThatThrownBy(() -> inspeccionar(datos, ext))
                .isInstanceOf(MallaInvalidaException.class).hasMessageContaining(mensaje);
    }

    @Test
    void unStlBinarioCortadoSeRechazaExplicandoCuantoFalta() {
        byte[] completo = stlBinario(1, "binary");

        rechaza(Arrays.copyOf(completo, completo.length - 120), "stl", "cortado o corrupto");
    }

    @Test
    void unStlBinarioCortadoConEncabezadoSolidTambienSeExplica() {
        byte[] completo = stlBinario(1, "solid algo");

        rechaza(Arrays.copyOf(completo, completo.length - 120), "stl", "cortado o corrupto");
    }

    @Test
    void unStlBinarioConCoordenadasInvalidasSeRechaza() {
        byte[] datos = stlBinario(1, "binary");
        ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN).putFloat(84 + 12, Float.NaN);   // un vértice del primer triángulo

        rechaza(datos, "stl", "coordenadas inválidas");
    }

    @Test
    void unStlAsciiIncompletoSeRechaza() {
        String texto = new String(stlAscii(false), StandardCharsets.US_ASCII);
        byte[] cortado = texto.substring(0, texto.length() - 60).getBytes(StandardCharsets.US_ASCII);

        rechaza(cortado, "stl", "incompleto");
    }

    @Test
    void unStlAsciiSinCerrarPeroConTodosLosTriangulosSoloAvisa() throws Exception {
        InformeMalla r = inspeccionar(stlAscii(false), "stl");

        assertThat(r.triangulos()).isEqualTo(12);
        assertThat(r.advertencias()).singleElement().asString().contains("endsolid");
    }

    @Test
    void unTextoCualquieraRenombradoComoStlSeRechaza() {
        byte[] texto = ("Hola, esto es una nota para el laboratorio. ".repeat(10)).getBytes(StandardCharsets.UTF_8);

        rechaza(texto, "stl", "No parece un STL");
    }

    @Test
    void unStlDeCeroTriangulosOMuyChicoSeRechaza() {
        rechaza(new byte[20], "stl", "demasiado chico");
        rechaza("solid vacio\nendsolid vacio\n".repeat(5).getBytes(StandardCharsets.US_ASCII), "stl", "no tiene triángulos");
    }

    @Test
    void unObjBinarioSeRechaza() {
        byte[] datos = new byte[2000];
        Arrays.fill(datos, (byte) 7);
        datos[10] = 0;

        rechaza(datos, "obj", "binario");
    }

    @Test
    void unObjConCarasQueApuntanAVerticesInexistentesSeRechaza() {
        byte[] datos = obj("f 1 2 99\n");

        rechaza(datos, "obj", "apuntan al vértice 99");
    }

    @Test
    void unObjSinVerticesOConCarasDeDosVerticesSeRechaza() {
        rechaza("# nada\ng grupo\n".getBytes(StandardCharsets.US_ASCII), "obj", "No parece un OBJ válido");
        rechaza("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2\n".getBytes(StandardCharsets.US_ASCII), "obj", "menos de 3 vértices");
    }

    @Test
    void unaCoordenadaIlegibleDiceEnQueLineaEstaElError() {
        rechaza("v 0 0 0\nv 1 hola 0\nv 0 1 0\n".getBytes(StandardCharsets.US_ASCII), "obj", "línea 2");
    }

    @Test
    void unArchivoSinSaltosDeLineaNoPuedeAgotarLaMemoria() {
        byte[] enorme = ("v " + "1".repeat(50_000)).getBytes(StandardCharsets.US_ASCII);

        rechaza(enorme, "obj", "línea de más de");
    }

    @Test
    void unPlyConEncabezadoCortadoOSinCoordenadasSeRechaza() {
        rechaza("ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\n".getBytes(StandardCharsets.US_ASCII), "ply", "end_header");
        rechaza(("ply\nformat ascii 1.0\nelement vertex 1\nproperty float x\nproperty float y\nend_header\n1 2\n").getBytes(StandardCharsets.US_ASCII), "ply", "coordenada 'z'");
        rechaza("no soy un ply".getBytes(StandardCharsets.US_ASCII), "ply", "No es un PLY");
    }

    @Test
    void unPlyBinarioCortadoSeRechaza() {
        byte[] completo = plyBinario(ByteOrder.LITTLE_ENDIAN, -1);

        rechaza(Arrays.copyOf(completo, completo.length - 30), "ply", "El archivo está cortado");
    }

    @Test
    void unPlyConCarasQueApuntanAVerticesInexistentesSeRechaza() {
        rechaza(plyBinario(ByteOrder.LITTLE_ENDIAN, 500), "ply", "apunta al vértice 500");
    }

    @Test
    void unFormatoQueNoSeSoportaSeRechaza() {
        rechaza(stlAscii(true), "3mf", "Formato no soportado");
    }

    @Test
    void unaMallaMasPesadaQueElTopeSeRechaza() {
        MallaInspector chico = new MallaInspector(5);
        byte[] datos = stlBinario(1, "binary");

        assertThatThrownBy(() -> chico.inspeccionar(new ByteArrayInputStream(datos), datos.length, "stl"))
                .isInstanceOf(MallaInvalidaException.class).hasMessageContaining("máximo permitido");
    }

    // ── Avisos: el archivo sirve, pero algo huele raro ───────────────

    @Test
    void medidasEnMetrosOEnMicronesAvisan() throws Exception {
        assertThat(inspeccionar(stlBinario(0.0005, "binary"), "stl").advertencias())
                .singleElement().asString().contains("metros");
        assertThat(inspeccionar(stlBinario(50, "binary"), "stl").advertencias())
                .singleElement().asString().contains("muy grandes");
    }

    @Test
    void unStlConBytesDeMasAlFinalAvisaYElHashCubreElArchivoEntero() throws Exception {
        byte[] base = stlBinario(1, "binary");
        byte[] conCola = Arrays.copyOf(base, base.length + 10);

        InformeMalla r = inspeccionar(conCola, "stl");

        assertThat(r.triangulos()).isEqualTo(12);
        assertThat(r.advertencias()).singleElement().asString().contains("10 bytes de más");
        assertThat(r.sha256()).isEqualTo(sha256(conCola));
    }

    @Test
    void trianguloSinSuperficieAvisa() throws Exception {
        byte[] datos = stlBinario(1, "binary");
        // el primer triángulo pasa a tener sus tres vértices en el mismo punto
        ByteBuffer bb = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 6; i++) bb.putFloat(84 + 12 + 12 + 4 * i, bb.getFloat(84 + 12 + (i % 3) * 4));

        InformeMalla r = inspeccionar(datos, "stl");

        assertThat(r.advertencias()).singleElement().asString().contains("1 de 12 triángulos no tienen superficie");
    }

    @Test
    void unPlyOUnObjSinCarasEsUnaNubeDePuntosYAvisa() throws Exception {
        String ply = "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nend_header\n0 0 0\n10 0 0\n0 20 0\n";
        InformeMalla r = inspeccionar(ply.getBytes(StandardCharsets.US_ASCII), "ply");

        assertThat(r.triangulos()).isZero();
        assertThat(r.advertencias()).anyMatch(a -> a.contains("nube de puntos"));
    }

    @Test
    void elMismoContenidoDaSiempreElMismoHashYUnoDistintoDaOtro() throws Exception {
        byte[] a = stlBinario(1, "binary");
        byte[] b = stlBinario(2, "binary");

        assertThat(inspeccionar(a, "stl").sha256()).isEqualTo(inspeccionar(a.clone(), "stl").sha256());
        assertThat(inspeccionar(a, "stl").sha256()).isNotEqualTo(inspeccionar(b, "stl").sha256());
    }

    @Test
    void unArchivoGrandeSeProcesaEnStreamingSinProblemas() throws Exception {
        // 200.000 triángulos (~10 MB): cruza varios bloques de lectura
        int n = 200_000;
        ByteBuffer bb = ByteBuffer.allocate(84 + 50 * n).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(80);
        bb.putInt(n);
        IntStream.range(0, n).forEach(i -> {
            bb.putFloat(0).putFloat(0).putFloat(0);
            bb.putFloat(i % 50).putFloat(0).putFloat(0);
            bb.putFloat(0).putFloat(i % 40 + 1).putFloat(0);
            bb.putFloat(0).putFloat(0).putFloat(i % 30 + 1);
            bb.putShort((short) 0);
        });
        ByteArrayOutputStream sal = new ByteArrayOutputStream();
        sal.write(bb.array());

        InformeMalla r = inspeccionar(sal.toByteArray(), "stl");

        assertThat(r.triangulos()).isEqualTo(n);
        assertThat(r.dimX()).isEqualByComparingTo("49");
        assertThat(r.dimY()).isEqualByComparingTo("40");
        assertThat(r.dimZ()).isEqualByComparingTo("30");
    }
}
