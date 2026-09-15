# =========================
# Stage 1: Build
# =========================
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# Copy project source and SQLite JDBC dependency
COPY src ./src
COPY lib/sqlite-jdbc-3.53.4.0.jar ./lib/sqlite-jdbc-3.53.4.0.jar

# Compile all Java source files
RUN mkdir -p out && \
    find src -name "*.java" > sources.txt && \
    javac -cp "lib/sqlite-jdbc-3.53.4.0.jar" -d out @sources.txt


# =========================
# Stage 2: Runtime
# =========================
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy compiled application
COPY --from=builder /app/out ./out

# Copy SQLite JDBC driver
COPY --from=builder /app/lib/sqlite-jdbc-3.53.4.0.jar ./lib/sqlite-jdbc-3.53.4.0.jar

# Render provides PORT automatically.
# ApiConfig reads PORT and falls back to 8080 locally.
EXPOSE 8080

# Start the existing application entry point.
CMD ["java", "-cp", "out:lib/sqlite-jdbc-3.53.4.0.jar", "com.sce.Main"]