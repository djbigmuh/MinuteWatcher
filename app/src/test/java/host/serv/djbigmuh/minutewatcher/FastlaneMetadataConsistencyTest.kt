package host.serv.djbigmuh.minutewatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FastlaneMetadataConsistencyTest {
    private fun findProjectRoot(): File {
        val rootPath =
            System.getProperty("minutewatcher.projectRoot")
                ?: error("System property 'minutewatcher.projectRoot' is not set")
        val dir = File(rootPath)
        if (!dir.exists()) {
            error("Project root does not exist at $rootPath")
        }
        return dir
    }

    private fun getPngDimensionsFromIhdr(file: File): Pair<Int, Int> {
        val bytes = file.readBytes()
        assertTrue("PNG file must be at least 24 bytes long", bytes.size >= 24)
        val expectedSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in 0..7) {
            assertEquals("PNG signature byte $i must match", expectedSignature[i], bytes[i])
        }
        val width =
            ((bytes[16].toInt() and 0xFF) shl 24) or
                ((bytes[17].toInt() and 0xFF) shl 16) or
                ((bytes[18].toInt() and 0xFF) shl 8) or
                (bytes[19].toInt() and 0xFF)
        val height =
            ((bytes[20].toInt() and 0xFF) shl 24) or
                ((bytes[21].toInt() and 0xFF) shl 16) or
                ((bytes[22].toInt() and 0xFF) shl 8) or
                (bytes[23].toInt() and 0xFF)
        return Pair(width, height)
    }

    @Test
    fun `fastlane metadata structure and limits are valid`() {
        val root = findProjectRoot()
        val fastlaneDir = File(root, "fastlane/metadata/android")
        assertTrue("fastlane/metadata/android directory must exist", fastlaneDir.exists())

        val versionCode = BuildConfig.VERSION_CODE
        val locales = listOf("en-US", "ru")

        locales.forEach { locale ->
            val localeDir = File(fastlaneDir, locale)
            assertTrue("Locale directory $locale must exist", localeDir.exists())

            val titleFile = File(localeDir, "title.txt")
            assertTrue("title.txt must exist for $locale", titleFile.exists())
            val titleText = titleFile.readText().trim()
            assertTrue("title.txt in $locale must be <= 30 chars", titleText.length <= 30)

            val shortDescFile = File(localeDir, "short_description.txt")
            assertTrue("short_description.txt must exist for $locale", shortDescFile.exists())
            val shortDescText = shortDescFile.readText().trim()
            assertTrue("short_description.txt in $locale must be <= 80 chars", shortDescText.length <= 80)

            val fullDescFile = File(localeDir, "full_description.txt")
            assertTrue("full_description.txt must exist for $locale", fullDescFile.exists())
            val fullDescText = fullDescFile.readText().trim()
            assertTrue("full_description.txt in $locale must be <= 4000 chars", fullDescText.length <= 4000)

            val changelogFile = File(localeDir, "changelogs/$versionCode.txt")
            assertTrue("changelog file $versionCode.txt must exist for $locale", changelogFile.exists())
            val changelogText = changelogFile.readText().trim()
            assertTrue("changelog in $locale must be <= 500 chars", changelogText.length <= 500)
        }

        val iconFile = File(fastlaneDir, "en-US/images/icon.png")
        assertTrue("icon.png must exist", iconFile.exists())
        val (iconW, iconH) = getPngDimensionsFromIhdr(iconFile)
        assertEquals("icon.png width must be 512", 512, iconW)
        assertEquals("icon.png height must be 512", 512, iconH)

        val featureFile = File(fastlaneDir, "en-US/images/featureGraphic.png")
        assertTrue("featureGraphic.png must exist", featureFile.exists())
        val (featW, featH) = getPngDimensionsFromIhdr(featureFile)
        assertEquals("featureGraphic.png width must be 1024", 1024, featW)
        assertEquals("featureGraphic.png height must be 500", 500, featH)

        val screenshotsReadme = File(fastlaneDir, "en-US/images/phoneScreenshots/README.md")
        assertTrue("phoneScreenshots/README.md must exist", screenshotsReadme.exists())
    }
}
