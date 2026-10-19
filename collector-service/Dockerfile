# Build : contexte = racine du dépôt (docker compose build s'en charge)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY collector-service/pom.xml collector-service/pom.xml
COPY storage-service/pom.xml storage-service/pom.xml
COPY analytics-service/pom.xml analytics-service/pom.xml
COPY collector-service/src collector-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl collector-service -am package -DskipTests

FROM eclipse-temurin:17-jre
RUN useradd --system --no-create-home app
WORKDIR /app
COPY --from=build /build/collector-service/target/app.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=docker
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
