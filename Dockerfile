ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${MAVEN_IMAGE} AS builder

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN mvn -B -DskipTests package

FROM ${RUNTIME_IMAGE}

WORKDIR /app

COPY --from=builder /workspace/target/gateway_pro-1.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
