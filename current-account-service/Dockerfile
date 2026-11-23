FROM eclipse-temurin:25-jre
WORKDIR /app
COPY build/libs/current-account-service.jar app.jar
USER 1000
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
