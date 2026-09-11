FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY src/ src/
RUN ./mvnw --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S kimetabi && adduser -S -G kimetabi -u 10001 kimetabi
WORKDIR /app
COPY --from=build --chown=kimetabi:kimetabi \
  /workspace/target/kimetabi-backend-0.0.1-SNAPSHOT.jar /app/application.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/application.jar"]
