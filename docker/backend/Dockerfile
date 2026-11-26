# Runtime-образ бэкенда AI-Repo Inspector.
# Ожидается, что исполняемый jar уже собран локально:
#   cd backend && ./gradlew :modules:web:bootJar
FROM eclipse-temurin:24-jre

# Docker CLI нужен модулю analyzer-tests: он запускает тесты аудируемого проекта
# в отдельном контейнере через демон хоста (см. docker/docker-compose.yml,
# где проброшен /var/run/docker.sock). Без сокета критерий работает статически.
ARG DOCKER_CLI_VERSION=27.3.1
ARG DOCKER_CLI_ARCH=x86_64

WORKDIR /app

# git нужен для клонирования репозиториев по ссылке (модуль ingestion).
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates curl tar \
    && curl -fsSL "https://download.docker.com/linux/static/stable/${DOCKER_CLI_ARCH}/docker-${DOCKER_CLI_VERSION}.tgz" \
        -o /tmp/docker.tgz \
    && tar -xzf /tmp/docker.tgz --strip-components=1 -C /usr/local/bin docker/docker \
    && rm -f /tmp/docker.tgz \
    && apt-get purge -y curl \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Имя jar зафиксировано в backend/modules/web/build.gradle.kts (tasks.bootJar.archiveFileName).
COPY backend/modules/web/build/libs/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
