# AI-Repo Inspector

Интеллектуальный аудитор качества Git-репозиториев (учебный проект).
Сервис принимает ссылку на GitHub/GitLab или архив проекта, клонирует/распаковывает его,
прогоняет по набору критериев и формирует итоговый отчёт о «качестве» репозитория.

Проект описан по методологии SMARTER в [`docs/`](docs/).

---

## Стек

| Слой | Технологии |
|------|------------|
| Бэкенд | Kotlin, Spring Boot 3, Gradle (многомодульный), Spring Data JPA, PostgreSQL |
| JDK | 24 |
| Тесты | JUnit 5, Testcontainers (PostgreSQL в Docker) |
| Фронтенд | Angular 19 (TypeScript), Nginx |
| Деплой | Docker / docker-compose |

---

## Структура репозитория

```
ai-repo/
├── backend/                    # многомодульный Kotlin/Spring Boot монолит
│   ├── settings.gradle.kts     # список модулей
│   ├── build.gradle.kts        # корневой конфиг (Kotlin, Spring Boot BOM)
│   ├── gradlew / gradle/       # Gradle Wrapper
│   └── modules/
│       ├── common/             # общие утилиты (FileUtils и т.п.)
│       ├── domain/             # модели предметной области (DTO, enum)
│       ├── storage/            # БД: сущность project_check + репозиторий
│       ├── ingestion/          # загрузка проекта: git clone / распаковка архива
│       ├── analyzer-api/       # контракт критерия (CriterionAnalyzer)
│       ├── analyzer-readme/    # критерий «README»
│       ├── analyzer-code/      # критерий «Качество кода» (линтер/форматтер)
│       ├── analyzer-tests/     # критерий «Покрытие тестами» (стек-детект + Docker-раннер)
│       ├── analyzer-structure/ # критерий «Структура проекта»
│       ├── analyzer-git/       # критерий «История коммитов и вклад участников»
│       ├── analyzer-security/  # критерий «Безопасность» (поиск секретов)
│       ├── report/             # оркестрация критериев и сборка отчёта
│       ├── test-support/       # базовый класс для тестов с Testcontainers
│       └── web/                # Spring Boot application + REST API
├── frontend/                   # Angular-приложение (JS/TS)
│   ├── angular.json
│   ├── package.json
│   └── src/
├── docker/                     # Docker-раздел (вне бэкенда и фронтенда)
│   ├── docker-compose.yml
│   ├── backend/Dockerfile
│   └── frontend/{Dockerfile, nginx.conf}
├── docs/                       # презентация и ТЗ проекта
└── README.md
```

---

## Назначение модулей бэкенда

Каждый критерий аудита живёт в отдельном модуле (`analyzer-*`). Это позволяет
разным участникам команды работать параллельно, не конфликтуя в файлах.

### Инфраструктурные модули

- **common** — переиспользуемые утилиты (работа с файлами, чтение текста, расширения).
- **domain** — общие модели: `RepositorySource`, `CheckStatus`, `Finding`, `CriterionResult`, `AnalysisReport`.
- **storage** — слой БД. Содержит сущность `project_check` (поля `id, status, source_type, source_value, created_at`)
  и Spring Data JPA-репозиторий. Отвечает за хранение статуса задач проверки.
- **ingestion** — загрузка проекта на диск: `git clone` по ссылке или распаковка ZIP-архива.
  Возвращает `LoadedProject` (корневая директория).
- **analyzer-api** — интерфейс `CriterionAnalyzer` и контекст `AnalysisContext`. Контракт для всех критериев.
- **report** — `ReportService`: получает список всех `CriterionAnalyzer` и прогоняет их по проекту,
  собирая итоговый `AnalysisReport` (общая оценка = среднее по критериям).
- **web** — точка входа (`AiRepoApplication`), REST-контроллеры, DTO, оркестратор полного цикла проверки.
- **test-support** — `AbstractPostgresContainerTest`: абстрактный базовый класс для интеграционных тестов
  с настоящей БД (Testcontainers). От него наследуются тесты, которым нужен PostgreSQL.

### Модули-критерии (по одному на каждый пункт аудита)

| Модуль | Критерий | Что проверяет |
|--------|----------|---------------|
| `analyzer-readme` | README | Наличие README и ключевых разделов (установка, сборка, запуск) |
| `analyzer-code` | Качество кода | Конфиг линтера/форматтера, маркеры TODO/FIXME |
| `analyzer-tests` | Покрытие тестами | Тестовая директория/файлы + изолированный запуск тестов в Docker с флагами сбора покрытия |
| `analyzer-structure` | Структура проекта | Осмысленность архитектуры файлов, «плоскость» |
| `analyzer-git` | История коммитов и вклад участников | Оценка git-истории и процентного вклада участников |
| `analyzer-security` | Безопасность | Поиск секретов/ключей/токенов, оставленных в коде |

> Новый критерий добавляется автоматически: достаточно создать модуль с реализацией
> `CriterionAnalyzer` и подключить его в `web` — `ReportService` сам найдёт бин через Spring.

---

## Как добавить новый критерий

1. Создайте модуль `backend/modules/analyzer-<name>/`.
2. Подключите его в `backend/settings.gradle.kts` (`include("modules:analyzer-<name>")`).
3. В `build.gradle.kts` модуля добавьте зависимости на `analyzer-api`, `domain`, `common`, `spring-context`.
4. Реализуйте интерфейс `CriterionAnalyzer`, пометьте класс `@Component`.
5. Подключите модуль в `backend/modules/web/build.gradle.kts` (чтобы бин попал в classpath приложения).

---

## Модуль «Покрытие тестами» (analyzer-tests)

Критерий работает в два слоя:

1. **Статический анализ** — ищет тестовые каталоги/файлы и настроенные
   инструменты покрытия. Работает всегда, даже без Docker.
2. **Изолированный запуск** — по маркерам определяет технологический стек,
   поднимает одноразовый контейнер, выполняет тесты с флагами сбора покрытия
   и забирает отчёт. Оценка считается от фактического процента покрытия
   (цель — 80 %, минимум — 50 %, оба порога настраиваются).

### Поддерживаемые стеки

| Стек | Docker-образ | Сбор покрытия |
|------|--------------|---------------|
| Gradle (Kotlin/Java) | `eclipse-temurin:21-jdk` (с wrapper) / `gradle:8.10-jdk21` | `test` + init-скрипт JaCoCo, XML-отчёт |
| Maven | `maven:3.9-eclipse-temurin-21` | `jacoco:prepare-agent test jacoco:report` |
| Node.js (Jest / Vitest / Mocha+nyc) | `node:20-alpine` | `--coverage`, LCOV + Istanbul JSON |
| Python | `python:3.12-slim` | `pytest --cov`, `coverage.xml` + `lcov.info` |
| Go | `golang:1.23-alpine` | `go test -coverprofile=coverage.out` |
| .NET | `mcr.microsoft.com/dotnet/sdk:8.0` | `dotnet test --collect:"XPlat Code Coverage"` (Cobertura) |
| PHP | `composer:2` | `phpunit --coverage-clover` (если заявлен xdebug/pcov) |
| Ruby | `ruby:3.3` | `bundle exec rspec` + SimpleCov |
| Rust | `rust:1.83-slim` | `cargo test` (покрытие пока не собирается) |

Стеки ищутся по файлам-маркерам (`build.gradle.kts`, `pom.xml`, `package.json`,
`go.mod`, `Cargo.toml`, ...). Поддерживаются вложенные проекты монорепозитория
(глубина до 3 уровней) и несколько стеков в одном проекте.

### Изоляция среды выполнения

- копия проекта уезжает во временный именованный Docker volume — хостовая ФС
  и процесс бэкенда недоступны;
- `--read-only` rootfs, `--cap-drop ALL`, `no-new-privileges`, `--init`;
- лимиты `--cpus`, `--memory`, `--pids-limit`, отдельный tmpfs для `/tmp`;
- жёсткий таймаут на установку зависимостей и прогон тестов;
- по завершении контейнер, volume и временные каталоги удаляются в `finally`;
- сеть по умолчанию `bridge` (нужна пакетным менеджерам); для полностью
  герметичного прогона установите `network: none`.

### Конфигурация

```yaml
airepo:
  tests:
    enabled: true                  # false — только статический анализ
    mode: AUTO                     # AUTO | DOCKER | LOCAL | STATIC_ONLY
    allow-local-execution: false   # запуск на хосте (небезопасно, по умолчанию выключен)
    timeout: 10m
    target-coverage: 80            # 100 баллов при достижении
    warning-coverage: 50           # ниже — ERROR в отчёте
    max-detection-depth: 3
    max-plans: 8
    sandbox:
      network: bridge
      cpus: "2.0"
      memory: 2g
      tmpfs-size: 2g
      pids-limit: 512
      read-only-root-filesystem: true
      drop-all-capabilities: true
      no-new-privileges: true
      max-output-chars: 20000
      max-workspace-bytes: 536870912   # 512 МБ
    images:                        # переопределение образов по id стека
      node: node:22-alpine
```

Те же значения доступны через переменные окружения
(`AIREPO_TESTS_MODE`, `AIREPO_TESTS_NETWORK`, `AIREPO_TESTS_MEMORY`, ...);
они перечислены в `backend/modules/web/src/main/resources/application.yml`.

### Требования для реального запуска

Бэкенду нужен доступ к Docker-демону:

- **локально** — Docker CLI в `PATH` и запущенный демон;
- **docker-compose** — проброшенный `/var/run/docker.sock` (уже добавлен
  в `docker/docker-compose.yml`) и Docker CLI в образе бэкенда
  (`docker/backend/Dockerfile`).

Если Docker недоступен, критерий автоматически деградирует до статического
анализа и объясняет причину в отчёте. Локальный запуск на хосте выключен по
умолчанию: тесты чужого репозитория — это исполнение произвольного кода.

---

## Сборка и запуск в Docker (основной способ)

Предварительно на машине должны быть установлены: **Docker**, **Docker Compose**.

### 1. Собрать jar бэкенда

```bash
cd backend
./gradlew :modules:web:bootJar
cd ..
```

### 2. Собрать и запустить все сервисы

```bash
docker compose -f docker/docker-compose.yml up --build
```

Поднимутся три контейнера:

| Сервис | URL | Назначение |
|--------|-----|------------|
| `db` | `localhost:5432` | PostgreSQL |
| `backend` | `http://localhost:8080` | REST API |
| `frontend` | `http://localhost:4200` | Angular UI (Nginx) |

Фронтенд доступен по адресу `http://localhost:4200`, запросы к `/api/*` проксируются
Nginx'ом на бэкенд автоматически.

Остановить: `docker compose -f docker/docker-compose.yml down`
(вместе с данными БД — `down -v`).

---

## Локальная разработка (без Docker)

### Бэкенд

Нужны: JDK 24, Git. Для работы с БД локально поднимите PostgreSQL:

```bash
docker run -d --name airepo-db \
  -e POSTGRES_DB=airepo -e POSTGRES_USER=airepo -e POSTGRES_PASSWORD=airepo \
  -p 5432:5432 postgres:16-alpine
```

Запуск:

```bash
cd backend
./gradlew :modules:web:bootRun
```

Конфигурация подключения к БД берётся из переменных окружения `DB_URL`, `DB_USER`, `DB_PASSWORD`
(по умолчанию — `localhost:5432/airepo`).

### Фронтенд

Нужен Node.js 20+.

```bash
cd frontend
npm install
npm start        # dev-сервер на http://localhost:4200
```

Для обращения к API локально можно проксировать запросы на `http://localhost:8080`
(настройка прокси добавляется в `angular.json`/`proxy.conf.json`).

---

## Тесты

Обычные тесты (не требуют Docker):

```bash
cd backend
./gradlew test
```

Только тесты модуля покрытия (детект стеков, парсеры отчётов, флаги изоляции):

```bash
cd backend
./gradlew :modules:analyzer-tests:test
```

Интеграционные тесты с контейнерами (требуют запущенный Docker). Они помечены тэгом `container`
и по умолчанию пропускаются:

```bash
cd backend
./gradlew test -PincludeContainers
```

Пример наследования от базового класса с контейнером:

```kotlin
@Tag("container")
class MyRepositoryTest : AbstractPostgresContainerTest() {
    @Test
    fun `соединение работает`() {
        dataSource.connection.use { conn -> assertTrue(conn.isValid(1)) }
    }
}
```

---

## REST API

| Метод | Путь | Описание |
|-------|------|----------|
| `POST` | `/api/checks` | Создать задачу проверки `{"type":"GIT_URL","value":"https://..."}` → `{id, status}` |
| `POST` | `/api/checks/{id}/run` | Запустить анализ и получить `AnalysisReport` |
| `GET`  | `/api/checks/{id}` | Текущий статус задачи (для страницы ожидания) |

Формат ответа `AnalysisReport`:

```json
{
  "checkId": 1,
  "source": { "type": "GIT_URL", "value": "https://..." },
  "overallScore": 71.5,
  "criteria": [
    { "criterionId": "readme", "criterionName": "README", "score": 80.0,
      "summary": "...", "findings": [{ "file": "README.md", "line": 3, "message": "...", "severity": "WARNING" }] }
  ]
}
```

Результат критерия `tests` содержит фактическое покрытие и пояснения:

```json
{
  "criterionId": "tests",
  "criterionName": "Покрытие тестами",
  "score": 95.5,
  "summary": "Тестовая директория: есть; тестовых файлов: 42; стеки: Gradle (JVM) (корень); покрытие 76.4% (1200/1570); раннер: docker.",
  "findings": [
    { "message": "Покрытие (Gradle (JVM) (корень)): 76.4% (1200/1570).", "severity": "INFO" },
    { "message": "Покрытие 76.4% ниже целевого уровня 80.0%.", "severity": "WARNING" }
  ]
}
```

---

## Планы / TODO

- [ ] ИИ-агент: отправить результаты проверки в LLM и сформировать «todo исправлений» (ноябрь).
- [ ] Кастомные критерии, задаваемые через форму на фронте.
- [ ] Запуск анализа в фоне (страница ожидания с опросом статуса).
- [x] Углубить критерий покрытия: определение стека, изолированный запуск тестов
      в Docker и вычисление фактического покрытия.
- [ ] Добавить сбор покрытия для Rust (`cargo-llvm-cov`) и кеширование зависимостей
      между прогонами, чтобы ускорить повторные проверки.
