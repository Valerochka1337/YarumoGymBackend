package tech.valerochkagym.service.ai

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** Offline shadow comparison. It never inserts a run, proposal, presentation or chat message. */
class CoachEpisodeReplayTest {
  @Test
  fun `saved anonymized episodes compare old and new decisions without publication`() {
    val configured = System.getenv("COACH_EPISODES_DIR")
    assumeTrue(!configured.isNullOrBlank())
    val directory = Path.of(configured ?: "")
    assumeTrue(Files.isDirectory(directory))
    val json = JsonMapper.builder().build()
    val codec = CoachRunTools(json)
    val files =
      Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".json") }.sorted().toList()
      }
    assertTrue(files.isNotEmpty(), "No anonymized episodes")
    val report =
      files.mapIndexed { index, file ->
        val envelope = Files.newInputStream(file).use(json::readTree)
        val snapshot = envelope["snapshot"] ?: envelope
        require(snapshot["original_plan"]?.get("complete")?.asBoolean() == true) {
          "Episode ${index + 1} has no complete initial plan"
        }
        val legacy = snapshot.deepCopy() as ObjectNode
        (legacy["original_plan"] as ObjectNode).put("complete", false)
        val owner = UUID(0, 0)
        val args = json.createObjectNode()
        val before = codec.assessment(owner, legacy, args)
        val after = codec.assessment(owner, snapshot, args)
        mapOf(
          "episode" to index + 1,
          "oldKind" to before["kind"].asString(),
          "oldReason" to before["reason_code"].asString(),
          "oldOperations" to before["operations"].size(),
          "newKind" to after["kind"].asString(),
          "newReason" to after["reason_code"].asString(),
          "newOperations" to after["operations"].size(),
        )
      }
    val target = Path.of("build/reports/coach-v2-shadow/decisions.json")
    Files.createDirectories(target.parent)
    Files.writeString(target, json.writeValueAsString(report))
  }
}
