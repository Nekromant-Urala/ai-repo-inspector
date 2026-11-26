package com.airepo.report

import com.airepo.agent.AgentPromptBuilder
import com.airepo.agent.AgentProperties
import com.airepo.agent.AgentResultParser
import com.airepo.agent.AnalysisAgent
import com.airepo.agent.LlmClient
import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.domain.CriterionResult
import com.airepo.domain.CustomCriterion
import com.airepo.domain.RepositorySource
import com.airepo.domain.SourceType
import com.airepo.ingestion.LoadedProject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportServiceTest {

    @Test
    fun `analyze собирает встроенные критерии и отдаёт кастомные без LLM`() {
        val analyzers = listOf(
            FakeAnalyzer("readme", "README", 80.0),
            FakeAnalyzer("tests", "Тесты", 60.0),
        )
        val agent = AnalysisAgent(
            client = object : LlmClient {
                override fun chat(system: String, user: String): String =
                    throw AssertionError("агент выключен, LLM не вызывается")
            },
            props = AgentProperties().apply { enabled = false },
            promptBuilder = AgentPromptBuilder(),
            parser = AgentResultParser(),
        )
        val service = ReportService(analyzers, agent)

        val custom = listOf(CustomCriterion("Доступность", "от 0 до 10", "проверить alt"))
        val report = service.analyze(
            loaded = LoadedProject(
                rootDir = createTempDirectory("airepo-report-test"),
                source = RepositorySource(SourceType.GIT_URL, "https://github.com/owner/project"),
            ),
            checkId = 1L,
            customCriteria = custom,
        )

        assertEquals(listOf("readme", "tests"), report.criteria.map { it.criterionId })
        assertEquals(custom, report.customCriteria)
        assertTrue(report.customAssessments.isEmpty())
        assertTrue(report.recommendations.isEmpty())
        assertEquals(70.0, report.overallScore)
    }

    private class FakeAnalyzer(
        override val id: String,
        override val name: String,
        private val score: Double,
    ) : CriterionAnalyzer {
        override fun analyze(context: AnalysisContext): CriterionResult =
            CriterionResult(criterionId = id, criterionName = name, score = score, summary = "ok")
    }
}