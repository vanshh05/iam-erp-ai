# ── Stage 1: Build with Maven ──────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# ── Stage 2: Run with JRE ───────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/iam-erp-ai-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
