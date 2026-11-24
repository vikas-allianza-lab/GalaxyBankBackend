
# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy Maven project files
COPY pom.xml .
COPY src ./src

# Build Spring Boot application
RUN mvn -B clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy generated Spring Boot JAR
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Start application using Render's PORT
ENTRYPOINT ["sh", "-c", "java -Xmx400m -Dserver.port=${PORT:-8080} -jar app.jar"]
