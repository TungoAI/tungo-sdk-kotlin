package kz.tungo.sdk.kotlin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The README states the facts of this build. Its code snippets are compiled by the :examples project. */
class ReadmeTest {
    private val readme: String by lazy { File(System.getProperty("tungo.readme") ?: "README.md").readText(Charsets.UTF_8) }

    @Test
    fun readmeNamesTheRealCoordinatesVersionAndHost() {
        assertTrue("kz.tungo:tungo-sdk-kotlin:${TungoSdk.VERSION}" in readme, "the install line names this version")
        assertTrue(TungoSdk.DEFAULT_BASE_URL in readme)
        assertFalse(("api.tungo." + "ai") in readme)
    }

    @Test
    fun readmeCoversTheRequiredTopics() {
        for (topic in listOf("Errors", "Retries", "Timeouts", "Pagination", "Streaming", "Webhooks", "Money")) {
            assertTrue(Regex("^#+ .*$topic", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).containsMatchIn(readme), "a section about $topic")
        }
        for (name in listOf("TungoClient", "RequestOptions", "TungoApiException", "verifyWebhook", "TungoMoney", "NullableField")) {
            assertTrue(name in readme, name)
        }
    }
}
