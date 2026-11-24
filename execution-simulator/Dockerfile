FROM eclipse-temurin:25-jre
WORKDIR /app
COPY build/libs/execution-simulator.jar app.jar
USER 1000
EXPOSE 9878
ENTRYPOINT ["java", "-jar", "app.jar"]
