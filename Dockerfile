FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends iptables iproute2 curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/vertx-split-brain-1.0.0.jar /app/app.jar
EXPOSE 8080 5701
ENTRYPOINT ["java","-jar","/app/app.jar"]
