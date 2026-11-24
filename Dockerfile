# Build stage: dependencies are resolved in their own layer so code changes rebuild quickly.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package dependency:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory=target/lib \
    && cp target/intelligent-inventory-management-system-*.jar target/app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home inventory
COPY --from=build /src/target/app.jar app.jar
COPY --from=build /src/target/lib lib
COPY database database
USER inventory
# Inside the container the API must listen on all interfaces; Compose publishes it to loopback only.
ENV INVENTORY_API_HOST=0.0.0.0 \
    INVENTORY_API_PORT=8080 \
    INVENTORY_MIGRATIONS_DIR=/app/database
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "app.jar:lib/*", "com.kshres31.inventory.InventoryApplication"]
CMD ["serve"]
