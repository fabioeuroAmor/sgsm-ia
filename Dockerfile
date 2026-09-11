# eclipse-temurin e a imagem mantida oficial para Java (openjdk:* foi
# descontinuada). Variante -jre (nao -jdk): so precisamos rodar o jar ja
# compilado, nao compilar.
FROM eclipse-temurin:21-jre

# Nao rodar como root
RUN groupadd -r sgsmia && useradd -r -g sgsmia sgsmia

WORKDIR /app

COPY target/sgsm-ia-0.0.1-SNAPSHOT.jar app.jar

RUN chown sgsmia:sgsmia app.jar
USER sgsmia

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8082/v3/api-docs || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
