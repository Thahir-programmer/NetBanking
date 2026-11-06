# ---- Stage 1: build the jar ---------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Copy only what dependency resolution needs first, so this slow layer is cached
# until pom.xml changes (code edits do not re-download the internet)
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline

COPY src src
# Tests are skipped here: they need Docker themselves and run in CI instead
RUN ./mvnw -q package -DskipTests

# ---- Stage 2: small runtime image (JRE only, no Maven, no source) -------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Never run the app as root inside the container
RUN useradd --system --no-create-home --uid 10001 appuser
COPY --from=build /workspace/target/netbanking-*.jar app.jar
USER appuser

EXPOSE 8080
# Size the heap from the container's memory limit instead of the host's
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
