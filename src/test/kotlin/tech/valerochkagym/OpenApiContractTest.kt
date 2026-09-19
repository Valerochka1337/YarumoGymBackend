package tech.valerochkagym

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class OpenApiContractTest {
  private val json = JsonMapper.builder().build()

  @Test
  fun `checked in OpenAPI matches Coach Run origin and session model contracts`() {
    val schemas =
      json.readTree(Files.readString(Path.of("docs/openapi.json")))["components"]["schemas"]
    val run = schemas["CoachRunResponse"]
    val session = schemas["CoachSessionUpdateRequest"]

    assertTrue(run["required"].any { it.asString() == "origin" })
    assertEquals("USER", run["properties"]["origin"]["enum"][0].asString())
    assertEquals("COACH", run["properties"]["origin"]["enum"][1].asString())

    assertFalse(session["required"].any { it.asString() == "model" })
    assertEquals("string", session["properties"]["model"]["type"][0].asString())
    assertEquals("null", session["properties"]["model"]["type"][1].asString())
    assertEquals(200, session["properties"]["model"]["maxLength"].asInt())
  }
}
