# Build multi-stage, vedi consumer-service/Dockerfile per la spiegazione.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Cartella dei PDF generati: montata come volume dal docker-compose, cosi'
# i report sopravvivono a una ricreazione del container e restano
# raggiungibili dall'host allo stesso percorso usato in modalita' locale.
VOLUME /app/reports

EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
