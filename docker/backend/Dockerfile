# Runtime-образ бэкенда AI-Repo Inspector.
# Ожидается, что исполняемый jar уже собран локально:
#   cd backend && ./gradlew :modules:web:bootJar
FROM eclipse-temurin:24-jre

WORKDIR /app

# git нужен для клонирования репозиториев по ссылке (модуль ingestion).
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

# Имя jar зафиксировано в backend/modules/web/build.gradle.kts (tasks.bootJar.archiveFileName).
COPY backend/modules/web/build/libs/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]