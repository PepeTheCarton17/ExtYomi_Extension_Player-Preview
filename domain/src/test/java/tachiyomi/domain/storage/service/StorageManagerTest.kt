package tachiyomi.domain.storage.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class StorageManagerTest {

    @Test
    fun `numbered duplicate names match only managed folders with a numeric suffix`() {
        "downloads (1)".isNumberedDuplicateOf("downloads") shouldBe true
        "mpv-config (12)".isNumberedDuplicateOf("mpv-config") shouldBe true
        "local(2)".isNumberedDuplicateOf("local") shouldBe true
        "localnovel (30)".isNumberedDuplicateOf("localnovel") shouldBe true

        "downloads".isNumberedDuplicateOf("downloads") shouldBe false
        "downloads (1) extra".isNumberedDuplicateOf("downloads") shouldBe false
        "other (1)".isNumberedDuplicateOf("downloads") shouldBe false
        "downloads (one)".isNumberedDuplicateOf("downloads") shouldBe false
    }
}
