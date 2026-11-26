package com.airepo.analyzer.tests.coverage

import com.airepo.analyzer.tests.stack.CoverageArtifact
import com.airepo.analyzer.tests.stack.CoverageFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverageParserTest {

    private val parser = CoverageParser()

    @Test
    fun `jacoco xml aggregates all modules`(@TempDir root: Path) {
        writeJacoco(root, "module-a", covered = 80, missed = 20)
        writeJacoco(root, "module-b", covered = 40, missed = 60)

        val report = parser.parse(
            listOf(
                CoverageArtifact(
                    "**/build/reports/jacoco/test/jacocoTestReport.xml",
                    CoverageFormat.JACOCO_XML,
                ),
            ),
            root,
        )

        assertNotNull(report)
        assertEquals(120L, report.covered)
        assertEquals(200L, report.total)
        assertEquals(60.0, report.percent, 0.001)
        assertEquals(2, report.files)
    }

    @Test
    fun `cobertura xml reads line counters`(@TempDir root: Path) {
        val file = root.resolve("coverage.xml")
        Files.writeString(
            file,
            """<coverage line-rate="0.75" lines-valid="40" lines-covered="30"></coverage>""",
        )

        val report = parser.parseFile(file, CoverageFormat.COBERTURA_XML)

        assertNotNull(report)
        assertEquals(30L, report.covered)
        assertEquals(40L, report.total)
        assertEquals(75.0, report.percent, 0.001)
    }

    @Test
    fun `cobertura without summary counts line hits`(@TempDir root: Path) {
        val file = root.resolve("cobertura.xml")
        Files.writeString(
            file,
            """
            <coverage>
              <packages>
                <package name="app">
                  <classes>
                    <class name="A">
                      <lines>
                        <line number="1" hits="1"/>
                        <line number="2" hits="0"/>
                        <line number="3" hits="3"/>
                      </lines>
                    </class>
                  </classes>
                </package>
              </packages>
            </coverage>
            """.trimIndent(),
        )

        val report = parser.parseFile(file, CoverageFormat.COBERTURA_XML)

        assertNotNull(report)
        assertEquals(2L, report.covered)
        assertEquals(3L, report.total)
    }

    @Test
    fun `lcov sums all files`(@TempDir root: Path) {
        val file = root.resolve("lcov.info")
        Files.writeString(
            file,
            """
            TN:
            SF:src/a.js
            LF:10
            LH:7
            end_of_record
            SF:src/b.js
            LF:5
            LH:3
            end_of_record
            """.trimIndent(),
        )

        val report = parser.parseFile(file, CoverageFormat.LCOV)

        assertNotNull(report)
        assertEquals(10L, report.covered)
        assertEquals(15L, report.total)
    }

    @Test
    fun `go cover profile counts statements`(@TempDir root: Path) {
        val file = root.resolve("coverage.out")
        Files.writeString(
            file,
            """
            mode: set
            example.com/pkg/a.go:1.1,2.2 2 1
            example.com/pkg/a.go:4.1,6.2 3 0
            """.trimIndent(),
        )

        val report = parser.parseFile(file, CoverageFormat.GO_COVER)

        assertNotNull(report)
        assertEquals(2L, report.covered)
        assertEquals(5L, report.total)
        assertEquals(40.0, report.percent, 0.001)
    }

    @Test
    fun `clover xml reads project metrics`(@TempDir root: Path) {
        val file = root.resolve("clover.xml")
        Files.writeString(
            file,
            """
            <coverage>
              <project name="app">
                <metrics statements="20" coveredstatements="15"/>
              </project>
            </coverage>
            """.trimIndent(),
        )

        val report = parser.parseFile(file, CoverageFormat.CLOVER_XML)

        assertNotNull(report)
        assertEquals(15L, report.covered)
        assertEquals(20L, report.total)
    }

    @Test
    fun `istanbul json summary reads line totals`(@TempDir root: Path) {
        val file = root.resolve("coverage-summary.json")
        Files.writeString(
            file,
            """
            {"total":{"lines":{"total":40,"covered":30,"pct":75},
                      "statements":{"total":50,"covered":40,"pct":80}}}
            """.trimIndent(),
        )

        val report = parser.parseFile(file, CoverageFormat.ISTANBUL_JSON)

        assertNotNull(report)
        assertEquals(30L, report.covered)
        assertEquals(40L, report.total)
    }

    @Test
    fun `simplecov json ignores non executable lines`(@TempDir root: Path) {
        val file = root.resolve(".resultset.json")
        Files.writeString(
            file,
            """
            {"RSpec":{"coverage":{"/repo/app/user.rb":{"lines":[1,null,0,2]}}}}
            """.trimIndent(),
        )

        val report = parser.parseFile(file, CoverageFormat.SIMPLECOV_JSON)

        assertNotNull(report)
        assertEquals(2L, report.covered)
        assertEquals(3L, report.total)
    }

    @Test
    fun `first matching format wins so coverage is not doubled`(@TempDir root: Path) {
        Files.writeString(root.resolve("coverage.xml"), """<coverage lines-valid="10" lines-covered="8"/>""")
        Files.writeString(root.resolve("lcov.info"), "SF:a.js\nLF:10\nLH:5\nend_of_record\n")

        val report = parser.parse(
            listOf(
                CoverageArtifact("coverage.xml", CoverageFormat.COBERTURA_XML),
                CoverageArtifact("lcov.info", CoverageFormat.LCOV),
            ),
            root,
        )

        assertNotNull(report)
        assertEquals(8L, report.covered)
        assertEquals(10L, report.total)
    }

    @Test
    fun `missing or broken artifacts yield null`(@TempDir root: Path) {
        assertNull(parser.parse(emptyList(), root))
        assertNull(parser.parse(listOf(CoverageArtifact("coverage.xml", CoverageFormat.COBERTURA_XML)), root))

        val broken = root.resolve("lcov.info")
        Files.writeString(broken, "SF:a.js\n")
        assertNull(parser.parseFile(broken, CoverageFormat.LCOV))

        val malformedXml = root.resolve("jacoco.xml")
        Files.writeString(malformedXml, "<report><counter")
        assertNull(parser.parseFile(malformedXml, CoverageFormat.JACOCO_XML))
    }

    @Test
    fun `glob matcher supports double star and root level files`() {
        assertTrue(GlobMatcher.matches("**/build/reports/jacoco/test/jacocoTestReport.xml", "build/reports/jacoco/test/jacocoTestReport.xml"))
        assertTrue(GlobMatcher.matches("**/build/reports/jacoco/test/jacocoTestReport.xml", "api/build/reports/jacoco/test/jacocoTestReport.xml"))
        assertTrue(GlobMatcher.matches("coverage/lcov.info", "coverage/lcov.info"))
        assertTrue(GlobMatcher.matches("**/TestResults/**/coverage.cobertura.xml", "app/TestResults/abc/coverage.cobertura.xml"))
        assertFalse(GlobMatcher.matches("coverage/lcov.info", "api/coverage/lcov.info"))
        assertFalse(GlobMatcher.matches("**/coverage.xml", "coverage.xml.bak"))
    }

    private fun writeJacoco(root: Path, module: String, covered: Int, missed: Int) {
        val directory = root.resolve("$module/build/reports/jacoco/test")
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("jacocoTestReport.xml"),
            """
            <report name="$module">
              <counter type="INSTRUCTION" missed="10" covered="30"/>
              <counter type="LINE" missed="$missed" covered="$covered"/>
            </report>
            """.trimIndent(),
        )
    }
}
