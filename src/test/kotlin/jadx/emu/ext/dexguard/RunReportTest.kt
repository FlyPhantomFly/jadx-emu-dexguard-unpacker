package jadx.emu.ext.dexguard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class RunReportTest {

    @Test fun returnToJavaSection() {
        val hit = NativeRoutineScan.Result(
            "libdg.so", "arm64-v8a", listOf(
                NativeRoutineScan.Match("\$a", 0x210, 0x20010, 12, 0x55AA55AA),
                NativeRoutineScan.Match("\$f", 0x420, 0x20220, 13, 0x2A55AA55),
            ),
        )
        val path = Files.createTempFile("report", ".json")
        path.toFile().deleteOnExit()
        try {
            RunReport.write(path, emptyList(), emptyList(), DeviceProfile(), "com.example", listOf(hit))
            val json = Files.readString(path)
            assertTrue(json.contains("\"returnToJava\""))
            assertTrue(json.contains("\"found\": true"))
            assertTrue(json.contains("\"lib\": \"libdg.so\""))
            assertTrue(json.contains("\"pattern\": \"\$a\""))
            assertTrue(json.contains("\"virtualAddress\": \"0x00020010\""))
            assertTrue(json.contains("\"constant\": \"0x55aa55aa\""))
            assertTrue(json.contains("\"register\": 12"))
            // Braces are balanced (a handcrafted writer could go one off).
            assertEquals(json.count { it == '{' }, json.count { it == '}' })
            assertEquals(json.count { it == '[' }, json.count { it == ']' })
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test fun returnToJavaAbsentWhenNothingFound() {
        val path = Files.createTempFile("report", ".json")
        path.toFile().deleteOnExit()
        try {
            RunReport.write(path, emptyList(), emptyList(), DeviceProfile(), null)
            val json = Files.readString(path)
            assertTrue(json.contains("\"found\": false"))
            assertTrue(json.contains("\"libs\": [\n    ]"))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
