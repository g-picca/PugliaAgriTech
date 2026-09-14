# Build multi-stage: la fase di compilazione (JDK completo, piu' pesante)
# non finisce nell'immagine finale, che contiene solo la JRE e il jar gia'
# costruito - stessa idea del build multi-stage gia' usata per i sensori
# C++ (sensors/*/Dockerfile).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copiati separatamente da src/ cosi' Docker riusa la cache degli strati
# (dipendenze Maven) finche' pom.xml non cambia, anche se il codice cambia.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
