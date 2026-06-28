FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/matching-engine-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xms512m", "-Xmx1g", "-jar", "app.jar"]