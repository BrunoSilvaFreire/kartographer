package io.kartographer.tasks.gcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun fixture(name: String): String =
    GcpTasksTest::class.java.getResource("/gcp/$name")!!.readText()

class GcpTasksTest {
    @Test
    fun `resource decision creates absent resources and reuses matching resources`() {
        val absent = parseGcpObject(fixture("absent.json"))
        val matching = parseGcpObject(fixture("matching.json"))
        assertEquals(EnsureDecision.Create, decideEnsure(absent, mapOf("location" to "us-central1")))
        assertEquals(EnsureDecision.Reuse, decideEnsure(matching, mapOf("location" to "us-central1")))
    }

    @Test
    fun `resource decision reports field level conflicts`() {
        assertEquals(
            EnsureDecision.Conflict(mapOf("location" to ("europe-west1" to "us-central1"))),
            decideEnsure(parseGcpObject(fixture("conflicting.json")), mapOf("location" to "us-central1"))
        )
    }

    @Test
    fun `parses enabled services from gcloud JSON`() {
        assertEquals(setOf("iam.googleapis.com"), parseEnabledServices("""[{"config":{"name":"iam.googleapis.com"}}]"""))
        assertEquals(emptySet<String>(), parseEnabledServices("invalid"))
    }

    @Test
    fun `deleted workload identity pool is identified from JSON`() {
        assertEquals("DELETED", parsePoolState(fixture("pool-deleted.json")))
        assertEquals(null, parsePoolState("{}"))
    }

    @Test
    fun `disabled API is treated as absent only for dry-run planning`() {
        assertTrue(isMissingResource("PERMISSION_DENIED: Google Cloud KMS API has not been used in this project before or it is disabled"))
    }

    @Test
    fun `variables only set changed or missing values`() {
        val desired = mapOf("A" to "one", "B" to "two")
        assertEquals(mapOf("B" to "two"), variableDiff(mapOf("A" to "one", "B" to "old"), desired))
        assertTrue(decideVariableSet(null, "one"))
        assertFalse(decideVariableSet("one", "one"))
    }
}
