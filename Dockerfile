FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./

RUN chmod +x gradlew

RUN ./gradlew build -x test || true

COPY src src

RUN ./gradlew build -x test

CMD ["./gradlew", "run", "--no-daemon"]
