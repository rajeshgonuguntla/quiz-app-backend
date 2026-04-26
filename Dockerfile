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

# Expose the JVM debug port so a remote debugger can attach (use with JAVA_DEBUG_OPTS)
#EXPOSE 5005

#ENV GEMINI_API_KEY=AIzaSyBiYCrm8hDfIWRHjmNhgL3qWPtEjCCYHXg
#ENV GOOGLE_CLIENT_SECRET=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
#ENV PROXY_PASS=Citrix2027!
#ENV PROXY_HOST=gate.decodo.com
#ENV PROXY_USER=rajeshgonuguntla384@gmail.com
#ENV PROXY_PORT=7000


# Allow enabling remote debugging by passing JAVA_DEBUG_OPTS at runtime.
# Example: -e JAVA_DEBUG_OPTS='-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005'
# Set default JVM memory options if not provided
ENV JAVA_OPTS="-Xmx2g -Xms512m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

