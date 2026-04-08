# syntax=docker/dockerfile:1.7

# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy project files and build the application jar
COPY . .
RUN chmod +x mvnw && ./mvnw -q clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre

# Install python + yt-dlp
RUN apt-get update && \
    apt-get install -y python3 python3-pip ffmpeg && \
    pip3 install yt-dlp --break-system-packages && \
    apt-get clean

WORKDIR /app

# Copy fat jar from builder
COPY --from=build /workspace/target/quiz-app-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

