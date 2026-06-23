# --- Build stage: compile + package the Spring Boot app ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first (only re-downloads when pom.xml changes)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Build the executable jar (tests run on CI, not in the image build)
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Runtime stage: small JRE image ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
# Config comes from env vars at runtime (see README "Deploy to Dokploy").
ENTRYPOINT ["java", "-jar", "app.jar"]
