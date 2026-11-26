package com.airepo.analyzer.tests.coverage

import com.airepo.analyzer.tests.stack.CoverageArtifact
import com.airepo.analyzer.tests.stack.CoverageFormat
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Разбирает файлы покрытия разных форматов в единый [CoverageReport].
 *
 * Артефакты перебираются по порядку: первый формат, для которого нашлись
 * файлы и получились данные, считается основным (чтобы не сложить один
 * и тот же результат дважды — например, `coverage.xml` и `lcov.info`).
 * Внутри одного формата отчёты по модулям складываются.
 */
@Component
class CoverageParser {

    private val objectMapper = ObjectMapper()

    /**
     * Ищет файлы по [artifacts] внутри [root] и агрегирует покрытие.
     *
     * @return `null`, если ни один файл не найден или все оказались пустыми
     */
    fun parse(artifacts: List<CoverageArtifact>, root: Path): CoverageReport? {
        if (artifacts.isEmpty() || !Files.isDirectory(root)) return null
        for (artifact in artifacts) {
            val matched = findFiles(root, artifact.glob)
            if (matched.isEmpty()) continue
            val reports = matched.mapNotNull { file -> parseFile(file, artifact.format) }
            if (reports.isNotEmpty()) return reports.reduce { acc, report -> acc + report }
        }
        return null
    }

    /** Разбирает один файл покрытия. Ошибка разбора не роняет анализ — возвращается `null`. */
    fun parseFile(file: Path, format: CoverageFormat): CoverageReport? = runCatching {
        when (format) {
            CoverageFormat.JACOCO_XML -> parseJacoco(file)
            CoverageFormat.COBERTURA_XML -> parseCobertura(file)
            CoverageFormat.LCOV -> parseLcov(file)
            CoverageFormat.GO_COVER -> parseGoCover(file)
            CoverageFormat.CLOVER_XML -> parseClover(file)
            CoverageFormat.SIMPLECOV_JSON -> parseSimpleCov(file)
            CoverageFormat.ISTANBUL_JSON -> parseIstanbul(file)
        }
    }.getOrNull()

    // ------------------------------------------------------------------ XML

    private fun parseJacoco(file: Path): CoverageReport? {
        val root = xmlRoot(file) ?: return null
        val counter = directCounters(root, "LINE").firstOrNull()
            ?: allCounters(root, "LINE").firstOrNull()
            ?: return null
        val covered = counter.getAttribute("covered").toLongOrNull() ?: return null
        val missed = counter.getAttribute("missed").toLongOrNull() ?: 0L
        return CoverageReport.of(covered, covered + missed, "JaCoCo")
    }

    private fun parseCobertura(file: Path): CoverageReport? {
        val root = xmlRoot(file) ?: return null
        val covered = root.getAttribute("lines-covered").toLongOrNull()
        val total = root.getAttribute("lines-valid").toLongOrNull()
        if (covered != null && total != null) return CoverageReport.of(covered, total, "Cobertura")

        // Часть инструментов отдаёт только построчные элементы <line hits="..."/>.
        val lines = root.getElementsByTagName("line")
        var coveredLines = 0L
        var totalLines = 0L
        for (index in 0 until lines.length) {
            val element = lines.item(index) as? Element ?: continue
            val hits = element.getAttribute("hits").toIntOrNull() ?: continue
            totalLines++
            if (hits > 0) coveredLines++
        }
        return CoverageReport.of(coveredLines, totalLines, "Cobertura")
    }

    private fun parseClover(file: Path): CoverageReport? {
        val root = xmlRoot(file) ?: return null
        val project = firstChild(root, "project") ?: root
        val metrics = firstChild(project, "metrics")
        val covered = metrics?.getAttribute("coveredstatements")?.toLongOrNull()
        val total = metrics?.getAttribute("statements")?.toLongOrNull()
        if (covered != null && total != null) return CoverageReport.of(covered, total, "Clover")

        // Fallback: суммируем метрики по файлам.
        var coveredStatements = 0L
        var totalStatements = 0L
        val files = root.getElementsByTagName("file")
        for (index in 0 until files.length) {
            val element = files.item(index) as? Element ?: continue
            val fileMetrics = firstChild(element, "metrics") ?: continue
            coveredStatements += fileMetrics.getAttribute("coveredstatements").toLongOrNull() ?: 0L
            totalStatements += fileMetrics.getAttribute("statements").toLongOrNull() ?: 0L
        }
        return CoverageReport.of(coveredStatements, totalStatements, "Clover")
    }

    private fun xmlRoot(file: Path): Element? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        // Защита от XXE: отключаем DOCTYPE и внешние сущности.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newDocumentBuilder().parse(file.toFile()).documentElement
    }.getOrNull()

    private fun directCounters(element: Element, type: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.tagName == "counter" && child.getAttribute("type") == type) result += child
        }
        return result
    }

    private fun allCounters(root: Element, type: String): List<Element> {
        val nodes = root.getElementsByTagName("counter")
        val result = mutableListOf<Element>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttribute("type") == type) result += element
        }
        return result
    }

    private fun firstChild(element: Element, name: String): Element? {
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.tagName == name) return child
        }
        return null
    }

    // ------------------------------------------------------------------ LCOV

    private fun parseLcov(file: Path): CoverageReport? {
        var total = 0L
        var covered = 0L
        Files.newBufferedReader(file).useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("LF:") -> total += line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    line.startsWith("LH:") -> covered += line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
            }
        }
        return CoverageReport.of(covered, total, "LCOV")
    }

    // -------------------------------------------------------------------- Go

    private fun parseGoCover(file: Path): CoverageReport? {
        var total = 0L
        var covered = 0L
        Files.newBufferedReader(file).useLines { lines ->
            lines.drop(1).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                val match = GO_COVER_LINE.find(line) ?: return@forEach
                val statements = match.groupValues[1].toLongOrNull() ?: return@forEach
                val count = match.groupValues[2].toLongOrNull() ?: return@forEach
                total += statements
                if (count > 0) covered += statements
            }
        }
        return CoverageReport.of(covered, total, "Go cover")
    }

    // ------------------------------------------------------------------ JSON

    private fun parseIstanbul(file: Path): CoverageReport? {
        val tree = objectMapper.readTree(file.toFile()) ?: return null
        val total = tree.path("total")
        val lines = total.path("lines")
        val lineTotal = lines.path("total").asLong(0)
        if (lineTotal > 0) return CoverageReport.of(lines.path("covered").asLong(0), lineTotal, "Istanbul")

        val statements = total.path("statements")
        val statementTotal = statements.path("total").asLong(0)
        return CoverageReport.of(statements.path("covered").asLong(0), statementTotal, "Istanbul")
    }

    private fun parseSimpleCov(file: Path): CoverageReport? {
        val tree = objectMapper.readTree(file.toFile()) ?: return null
        var total = 0L
        var covered = 0L

        for (command in tree.properties()) {
            collectSimpleCovLines(command.value.path("coverage"), onLine = { hits ->
                total++
                if (hits > 0) covered++
            })
        }
        if (total == 0L) {
            // На случай, если файл — сразу карта "файл -> {lines: [...]}".
            collectSimpleCovLines(tree, onLine = { hits ->
                total++
                if (hits > 0) covered++
            })
        }
        return CoverageReport.of(covered, total, "SimpleCov")
    }

    private fun collectSimpleCovLines(node: JsonNode, onLine: (Long) -> Unit) {
        if (!node.isObject) return
        for (entry in node.properties()) {
            val fileNode = entry.value
            val lines = fileNode.path("lines")
            if (!lines.isArray) continue
            for (line in lines) {
                if (line.isNull) continue
                if (line.isNumber) onLine(line.asLong()) else onLine(0L)
            }
        }
    }

    // ----------------------------------------------------------------- files

    private fun findFiles(root: Path, glob: String): List<Path> = runCatching {
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .filter { file -> GlobMatcher.matches(glob, root.relativize(file).toString().replace('\\', '/')) }
                .sorted()
                .toList()
        }
    }.getOrDefault(emptyList())

    private companion object {
        /** `<file>:<начало>,<конец> <инструкций> <срабатываний>` — формат Go cover profile. */
        val GO_COVER_LINE = Regex("""^.+?:\d+\.\d+,\d+\.\d+\s+(\d+)\s+(\d+)\s*$""")
    }
}

/**
 * Сопоставление пути с glob-шаблоном (`*`, `?`, `**`).
 *
 * Собственная реализация вместо `PathMatcher` нужна, чтобы результат
 * не зависел от платформенного разделителя путей (Windows/Linux).
 */
internal object GlobMatcher {

    private val cache = ConcurrentHashMap<String, Regex>()

    fun matches(glob: String, path: String): Boolean =
        cache.computeIfAbsent(glob) { toRegex(it) }.matches(path)

    fun toRegex(glob: String): Regex {
        val builder = StringBuilder("^")
        var index = 0
        while (index < glob.length) {
            val char = glob[index]
            when {
                char == '*' && index + 1 < glob.length && glob[index + 1] == '*' -> {
                    if (index + 2 < glob.length && glob[index + 2] == '/') {
                        // `**/` соответствует нулю или более каталогов.
                        builder.append("(?:.*/)?")
                        index += 3
                    } else {
                        builder.append(".*")
                        index += 2
                    }
                }
                char == '*' -> {
                    builder.append("[^/]*")
                    index++
                }
                char == '?' -> {
                    builder.append("[^/]")
                    index++
                }
                char in "\\^$.|+()[]{}" -> {
                    builder.append('\\').append(char)
                    index++
                }
                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        builder.append('$')
        return Regex(builder.toString())
    }
}
