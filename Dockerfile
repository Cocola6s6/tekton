# 阶段一：构建
FROM maven:3.9-eclipse-temurin-11-focal AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

# 阶段二：运行
FROM eclipse-temurin:11-jre-focal
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
