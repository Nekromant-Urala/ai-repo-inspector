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
│       ├── analyzer-tests/     # критерий «Покрытие тестами»
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
| `analyzer-tests` | Покрытие тестами | Наличие тестовой директории и тестовых файлов |
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

---

## Планы / TODO

- [ ] ИИ-агент: отправить результаты проверки в LLM и сформировать «todo исправлений» (ноябрь).
- [ ] Кастомные критерии, задаваемые через форму на фронте.
- [ ] Запуск анализа в фоне (страница ожидания с опросом статуса).
- [ ] Углубить критерии (реальный запуск линтеров, вычисление покрытия, полный git-анализ).