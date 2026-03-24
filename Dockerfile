# syntax=docker/dockerfile:1.7

# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy project files and build the application jar
COPY . .
RUN chmod +x mvnw && ./mvnw -q clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy fat jar from builder
COPY --from=build /workspace/target/quiz-app-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

