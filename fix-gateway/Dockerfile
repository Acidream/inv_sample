FROM eclipse-temurin:25-jre
WORKDIR /app
COPY build/libs/fix-gateway.jar app.jar
USER 1000
EXPOSE 8084
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
