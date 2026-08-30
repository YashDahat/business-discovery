# Stage 1: Build frontend
FROM node:20-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci --silent
COPY frontend/ ./
RUN npm run build

# Stage 2: Build backend (copy frontend dist before maven packages the jar)
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY mvnw .
COPY .mvn .mvn
COPY --from=frontend-build /frontend/dist ./src/main/resources/static/
RUN ./mvnw clean package -DskipTests

# Stage 3: Runtime
# Debian/glibc (not alpine/musl) — langchain4j all-MiniLM pulls onnxruntime native libs built for glibc.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
