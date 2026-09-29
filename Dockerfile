FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system scheduler && useradd --system --gid scheduler scheduler
COPY --from=build /workspace/scheduler-app/target/scheduler-app-0.1.0-SNAPSHOT.jar /app/app.jar
USER scheduler
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
