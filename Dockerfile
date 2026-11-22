# FamilyGuard Backend para Render (Docker). Solo el backend: el Administrador y el Agente no se publican.
#
# Pruebas: la suite completa se ejecuta en Windows con `mvnw.cmd clean verify` ANTES de subir a GitHub.
# Aquí no se ejecutan porque cinco pruebas del backend usan el Agente real, que cifra con DPAPI de Windows y no
# puede funcionar en un contenedor Linux. Este archivo solo empaqueta un código ya verificado.
#
# Sin credenciales: la base de datos, la clave del PIN y el resto de secretos llegan como variables de entorno
# de Render (perfil «remote»). Las cuatro líneas MySQL de application.properties no se tocan; el perfil las reemplaza.

# ---------- 1) Compilación ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# Reactor Maven: el pom padre lista los tres módulos, así que necesita sus pom.xml. El Agente se compila (sin
# pruebas) solo porque el backend lo declara como dependencia de prueba; no entra en la imagen final.
COPY pom.xml ./
COPY familyguard-admin/pom.xml familyguard-admin/pom.xml
COPY familyguard-agent/pom.xml familyguard-agent/pom.xml
COPY familyguard-agent/src/main familyguard-agent/src/main
COPY familyguard-backend/pom.xml familyguard-backend/pom.xml
COPY familyguard-backend/src/main familyguard-backend/src/main

# -pl/-am: solo el backend y lo que necesita. .mvn/jvm.config (almacén de certificados de Windows) no se copia.
RUN mvn -B -ntp -pl familyguard-backend -am -Dmaven.test.skip=true package \
 && cp familyguard-backend/target/familyguard-backend-*.jar /app.jar

# ---------- 2) Ejecución ----------
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system familyguard && useradd --system --gid familyguard --home-dir /app --shell /usr/sbin/nologin familyguard
WORKDIR /app
COPY --from=build --chown=familyguard:familyguard /app.jar /app/familyguard-backend.jar
USER familyguard

# Valores no secretos. Render puede sobrescribir cualquiera desde «Environment».
ENV SPRING_PROFILES_ACTIVE=remote \
    FAMILYGUARD_BIND_ADDRESS=0.0.0.0 \
    FAMILYGUARD_DB_POOL_SIZE=3 \
    SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1 \
    SERVER_TOMCAT_THREADS_MAX=40 \
    SERVER_TOMCAT_THREADS_MIN_SPARE=2 \
    TZ=UTC \
    JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=15 -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=32m -Xss512k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

# Render asigna el puerto en $PORT (10000 por defecto); 8080 solo si se ejecuta fuera de Render.
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar /app/familyguard-backend.jar"]
