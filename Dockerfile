FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY host.json .
COPY src ./src
RUN mvn -q -DskipTests clean package

FROM mcr.microsoft.com/azure-functions/java:4-java21
WORKDIR /home/site/wwwroot
COPY --from=build /workspace/target/azure-functions/functioneventrouting/ /home/site/wwwroot
ENV AzureWebJobsScriptRoot=/home/site/wwwroot \
    AzureFunctionsJobHost__Logging__Console__IsEnabled=true
EXPOSE 80
