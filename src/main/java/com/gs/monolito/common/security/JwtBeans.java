package com.gs.monolito.common.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Beans de JWT compartidos por todos los módulos (antes duplicados: cada uno
 * de los 5 microservicios tenía su propio {@code JwtAuthenticationConverter}
 * idéntico, y los 4 que no eran ms-auth apuntaban su {@code JwtDecoder} al
 * JWKS de ms-auth vía HTTP — acá sería un loopback del proceso a sí mismo,
 * así que en vez de eso se arma el {@link JwtDecoder} directo con la
 * {@link RSAKey} local (mismo keystore que ya usa el módulo auth para emitir).
 */
@Configuration
public class JwtBeans {

    private static final Logger log = LoggerFactory.getLogger(JwtBeans.class);

    /**
     * "file:./keys/..." (no "classpath:") a propósito: el keystore real ya NO
     * se versiona en git (contenía la clave privada real de firma de todos los
     * JWT del sistema). En su lugar, si el archivo no existe en disco, se
     * genera uno nuevo la primera vez que arranca (ver {@link #generarSiNoExiste}),
     * y de ahí en más persiste en ese path — en el VPS eso es un volumen Docker
     * montado en /app/keys, así que sobrevive a redeploys.
     */
    @Value("${gs.auth.keystore.path:file:./keys/gs-auth.p12}")
    private Resource keystorePath;

    @Value("${gs.auth.keystore.password:gs_keystore_2025}")
    private String keystorePassword;

    @Value("${gs.auth.keystore.alias:gs-auth}")
    private String keystoreAlias;

    /**
     * Carga la RSAKey desde el keystore PKCS12 persistido en disco, para que
     * los JWT emitidos antes de un reinicio sigan siendo válidos después.
     */
    @Bean
    public RSAKey rsaKey() {
        generarSiNoExiste();
        try (InputStream is = keystorePath.getInputStream()) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(is, keystorePassword.toCharArray());

            RSAPrivateKey privateKey = (RSAPrivateKey) keyStore.getKey(
                keystoreAlias, keystorePassword.toCharArray());
            RSAPublicKey publicKey = (RSAPublicKey)
                ((X509Certificate) keyStore.getCertificate(keystoreAlias)).getPublicKey();

            return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keystoreAlias)
                .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                "No se pudo cargar el keystore RSA desde: " + keystorePath, e);
        }
    }

    /**
     * Si el keystore configurado no existe en disco, genera uno nuevo con
     * {@code keytool} (viene incluido en cualquier JRE, sin dependencias
     * extra). Solo funciona para paths "file:" — si alguien configura
     * "classpath:" a propósito (bundlear su propio keystore en el jar), no se
     * toca y sigue el flujo normal (falla con un mensaje claro si tampoco existe).
     */
    private void generarSiNoExiste() {
        if (keystorePath.exists() || !keystorePath.isFile()) return;
        try {
            File file = keystorePath.getFile();
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();

            log.warn("[GS-AUTH] No existe el keystore JWT en {} — generando uno nuevo. "
                    + "Esto invalida cualquier JWT emitido anteriormente (esperable en el primer arranque).",
                    file.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", keystoreAlias,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-keystore", file.getAbsolutePath(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-dname", "CN=gs-monolito, OU=Laboratorio G&S"
            );
            pb.redirectErrorStream(true);
            Process proceso = pb.start();

            StringBuilder salida = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = reader.readLine()) != null) salida.append(linea).append('\n');
            }
            int codigo = proceso.waitFor();
            if (codigo != 0) {
                throw new IllegalStateException("keytool terminó con código " + codigo + ": " + salida);
            }
            log.info("[GS-AUTH] Keystore JWT generado en {}", file.getAbsolutePath());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el keystore JWT en " + keystorePath, e);
        }
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey) {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (Exception e) {
            throw new IllegalStateException("Error configurando JwtDecoder", e);
        }
    }

    @Bean
    public NimbusJwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /** Ver {@link JwtCookieAuthenticationFilter} — por qué NO es un BearerTokenResolver. */
    @Bean
    public JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter(JwtDecoder jwtDecoder,
                                                                       JwtAuthenticationConverter jwtAuthenticationConverter) {
        return new JwtCookieAuthenticationFilter(jwtDecoder, jwtAuthenticationConverter);
    }

    /**
     * Mapea el claim "roles" del JWT (CSV, ej: "ROLE_ADMIN,ROLE_TECNICO") a
     * authorities. Sin esto, hasRole(...) daría 403 en todos los endpoints
     * protegidos por rol.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String roles = jwt.getClaimAsString("roles");
            if (roles == null || roles.isBlank()) return Collections.emptyList();
            return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r))
                .collect(Collectors.toList());
        });
        return converter;
    }
}
