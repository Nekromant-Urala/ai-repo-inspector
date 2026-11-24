package com.airepo.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:audit;MODE=PostgreSQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
])
@AutoConfigureMockMvc
class ArchiveFlowTest @Autowired constructor(private val mvc: MockMvc, private val json: ObjectMapper) {
    @Test
    fun `uploaded ZIP is analyzed and report survives a separate request`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry("demo/README.md"))
            it.write("# Example".toByteArray())
            it.closeEntry()
        }
        val rules = """[{"name":"Документация","scale":"0–100","description":"Файл: README.md"}]"""
        val created = mvc.perform(multipart("/api/checks/archive")
            .file(MockMultipartFile("file", "demo.zip", "application/zip", bytes.toByteArray()))
            .file(MockMultipartFile("customCriteria", "", "application/json", rules.toByteArray())))
            .andExpect(status().isCreated).andReturn()
        val id = json.readTree(created.response.contentAsByteArray)["id"].asLong()
        mvc.perform(post("/api/checks/$id/run")).andExpect(status().isOk)
        mvc.perform(get("/api/checks/$id/report")).andExpect(status().isOk)
            .andExpect(jsonPath("$.source.value").value("demo.zip"))
            .andExpect(jsonPath("$.customCriteria[0].score").value(100))
        mvc.perform(post("/api/checks/$id/run")).andExpect(status().isConflict)
    }
}
