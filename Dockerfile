# ---------- build stage ----------
FROM eclipse-temurin:24 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src ./src
RUN ./mvnw -B clean package -DskipTests        # соберёт fat-jar

# ---------- runtime stage ----------
FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build /workspace/target/*-SNAPSHOT.jar app.jar
EXPOSE 8081 8082
ENTRYPOINT ["java","-jar","/app/app.jar"]
