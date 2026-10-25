package com.gs.monolito.pedidos.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La verificación del remitente a partir de lo que anota Gmail. Es lo que separa a un odontólogo real de alguien que
 * escribe "De: dr.perez@..." sin serlo, así que cada forma de fallar tiene que fallar cerrado.
 */
class AutenticacionTest {

    private static final String GMAIL = "mx.google.com";

    private static String[] cabecera(String cuerpo) {
        return new String[]{"mx.google.com;\n       " + cuerpo};
    }

    @Test
    void dmarcPassVerificaAlRemitente() {
        String[] h = cabecera("dkim=pass header.i=@clinica.com header.s=sel header.b=abc;\n"
                + "       spf=pass (google.com: domain of perez@clinica.com designates 1.2.3.4 as permitted sender) smtp.mailfrom=perez@clinica.com;\n"
                + "       dmarc=pass (p=NONE sp=NONE dis=NONE) header.from=clinica.com");

        assertThat(Autenticacion.remitenteVerificado(h, "perez@clinica.com", GMAIL)).isTrue();
    }

    @Test
    void unaFirmaDkimDelMismoDominioAlcanzaAunqueNoHayaDmarc() {
        String[] h = cabecera("dkim=pass header.d=clinica.com header.s=s1; spf=neutral smtp.mailfrom=perez@clinica.com");

        assertThat(Autenticacion.remitenteVerificado(h, "perez@clinica.com", GMAIL)).isTrue();
    }

    @Test
    void spfPassConElDominioDelRemitenteAlcanza() {
        String[] h = cabecera("dkim=none; spf=pass (google.com: domain of perez@clinica.com designates 1.2.3.4 as permitted sender) smtp.mailfrom=perez@clinica.com");

        assertThat(Autenticacion.remitenteVerificado(h, "perez@clinica.com", GMAIL)).isTrue();
    }

    @Test
    void unaFirmaValidaDeOtroDominioNoVerificaAlRemitente() {
        // El atacante firma con su propio dominio y escribe "De: perez@clinica.com": DKIM pasa, pero no es de clinica.com.
        String[] h = cabecera("dkim=pass header.i=@atacante.com header.s=s1; spf=pass smtp.mailfrom=bot@atacante.com; dmarc=fail (p=REJECT) header.from=clinica.com");

        assertThat(Autenticacion.remitenteVerificado(h, "perez@clinica.com", GMAIL)).isFalse();
    }

    @Test
    void unSubdominioDeLaMismaOrganizacionCuentaComoAlineado() {
        String[] h = cabecera("dkim=pass header.d=mail.clinica.com header.s=s1");

        assertThat(Autenticacion.remitenteVerificado(h, "perez@clinica.com", GMAIL)).isTrue();
    }

    @Test
    void sinNingunaVerificacionPasadaEsFalso() {
        String[] h = cabecera("dkim=none; spf=none smtp.mailfrom=perez@clinica.com; dmarc=none header.from=clinica.com");

        assertThat(Autenticacion.remitenteVerificado(h, "perez@clinica.com", GMAIL)).isFalse();
    }

    @Test
    void unaCabeceraQueNoEsDeNuestroServidorSeIgnora() {
        // Cualquiera puede escribir una cabecera "Authentication-Results" a mano; solo vale la del servidor receptor.
        String[] falsa = {"otro.servidor.com; dmarc=pass header.from=clinica.com"};

        assertThat(Autenticacion.remitenteVerificado(falsa, "perez@clinica.com", GMAIL)).isNull();
    }

    @Test
    void siHayUnaFalsaYLaVerdaderaSeUsaLaVerdadera() {
        String[] ambas = {"falso.com; dmarc=pass header.from=clinica.com", "mx.google.com; dmarc=fail header.from=clinica.com"};

        assertThat(Autenticacion.remitenteVerificado(ambas, "perez@clinica.com", GMAIL)).isFalse();
    }

    @Test
    void sinCabeceraNoSeSabe() {
        assertThat(Autenticacion.remitenteVerificado(null, "perez@clinica.com", GMAIL)).isNull();
        assertThat(Autenticacion.remitenteVerificado(new String[0], "perez@clinica.com", GMAIL)).isNull();
    }

    @Test
    void sinNombreDeServidorConfiguradoSeTomaLaPrimera() {
        String[] h = {"cualquiera.net; dmarc=pass header.from=clinica.com"};

        assertThat(Autenticacion.remitenteVerificado(h, "perez@clinica.com", "")).isTrue();
    }

    @Test
    void unRemitenteSinDominioNoSeVerifica() {
        assertThat(Autenticacion.remitenteVerificado(cabecera("dmarc=pass header.from=clinica.com"), null, GMAIL)).isFalse();
    }
}
