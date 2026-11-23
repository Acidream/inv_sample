FROM eclipse-temurin:25-jre
WORKDIR /app
COPY build/libs/transfer-service.jar app.jar
USER 1000
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
