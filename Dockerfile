# --- Etapa de build ------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copiamos primero solo el pom para cachear las dependencias en una capa
# aparte — un cambio de código no invalida esta capa.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Etapa de runtime ------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /build/target/monolito-*.jar app.jar

EXPOSE 8080

# /actuator/health es público sin auth (ver PublicEndpointsSecurityConfig) —
# usado por docker-compose.yml para que "frontend" espere a que Spring termine
# de levantar (depends_on: condition: service_healthy) en vez de arrancar a
# proxyear contra un backend que todavía no responde.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=6 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
