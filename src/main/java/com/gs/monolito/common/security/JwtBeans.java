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

import java.io.InputStream;
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

    @Value("${gs.auth.keystore.path:classpath:keys/gs-auth.p12}")
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
