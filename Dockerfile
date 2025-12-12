# -------- STAGE 1: build --------
FROM gradle:8.7-jdk17 AS build
WORKDIR /app

COPY build.gradle settings.gradle gradlew ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew build -x test --no-daemon

# -------- STAGE 2: runtime --------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copia o JAR gerado
COPY --from=build /app/build/libs/*.jar app.jar

# Porta padrão (ajuste se sua app usar outra)
EXPOSE 8080

# Variáveis recomendadas
ENV JAVA_OPTS="-XX:+UseContainerSupport"
ENV PORT=8080

# Comando de startup
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
