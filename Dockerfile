FROM gradle:8.7-jdk17 AS build
WORKDIR /app

COPY . .
RUN ./gradlew installDist -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/build/install/futebol-bot/ /app/
CMD ["/app/bin/futebol-bot"]
