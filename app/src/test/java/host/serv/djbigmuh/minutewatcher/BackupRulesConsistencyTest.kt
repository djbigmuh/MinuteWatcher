package host.serv.djbigmuh.minutewatcher

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Verifies consistency and completeness of cloud backup and device transfer configuration rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
class BackupRulesConsistencyTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `cloud backup exclusions contain datastore and shared preferences`() {
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        val rules = parseDataExtractionRules(parser)

        val expectedDatastore = BackupRule("file", "datastore/")
        val expectedSharedPref = BackupRule("sharedpref", ".")

        assertTrue(
            "Cloud backup must exclude DataStore files",
            rules.cloudBackupExcludes.contains(expectedDatastore),
        )
        assertTrue(
            "Cloud backup must exclude SharedPreferences",
            rules.cloudBackupExcludes.contains(expectedSharedPref),
        )
    }

    @Test
    fun `cloud backup exclusions match full backup content exclusions`() {
        val dataExtractionParser = context.resources.getXml(R.xml.data_extraction_rules)
        val dataExtractionRules = parseDataExtractionRules(dataExtractionParser)

        val fullBackupParser = context.resources.getXml(R.xml.full_backup_content)
        val fullBackupContent = parseFullBackupContent(fullBackupParser)

        assertEquals(
            "Exclusions in data_extraction_rules cloud-backup must match full_backup_content exclusions",
            fullBackupContent.excludes,
            dataExtractionRules.cloudBackupExcludes,
        )
    }

    @Test
    fun `backup rules exclude log files from cloud backup and device transfer`() {
        val dataExtractionParser = context.resources.getXml(R.xml.data_extraction_rules)
        val dataExtractionRules = parseDataExtractionRules(dataExtractionParser)

        val fullBackupParser = context.resources.getXml(R.xml.full_backup_content)
        val fullBackupContent = parseFullBackupContent(fullBackupParser)

        val expectedLog = BackupRule("file", "logs.txt")
        val expectedOldLog = BackupRule("file", "logs.old.txt")

        assertTrue(
            "full_backup_content must exclude logs.txt",
            fullBackupContent.excludes.contains(expectedLog),
        )
        assertTrue(
            "full_backup_content must exclude logs.old.txt",
            fullBackupContent.excludes.contains(expectedOldLog),
        )

        assertTrue(
            "cloud-backup must exclude logs.txt",
            dataExtractionRules.cloudBackupExcludes.contains(expectedLog),
        )
        assertTrue(
            "cloud-backup must exclude logs.old.txt",
            dataExtractionRules.cloudBackupExcludes.contains(expectedOldLog),
        )

        assertTrue(
            "device-transfer must exclude logs.txt",
            dataExtractionRules.deviceTransferExcludes.contains(expectedLog),
        )
        assertTrue(
            "device-transfer must exclude logs.old.txt",
            dataExtractionRules.deviceTransferExcludes.contains(expectedOldLog),
        )
    }

    @Test
    fun `device transfer inclusions contain shared preferences and datastore`() {
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        val rules = parseDataExtractionRules(parser)

        val expectedSharedPref = BackupRule("sharedpref", ".")
        val expectedDatastore = BackupRule("file", "datastore/")

        assertTrue(
            "Device transfer must include SharedPreferences",
            rules.deviceTransferIncludes.contains(expectedSharedPref),
        )
        assertTrue(
            "Device transfer must include DataStore files",
            rules.deviceTransferIncludes.contains(expectedDatastore),
        )
    }

    private data class BackupRule(
        val domain: String,
        val path: String,
    )

    private data class DataExtractionRules(
        val cloudBackupExcludes: Set<BackupRule>,
        val cloudBackupIncludes: Set<BackupRule>,
        val deviceTransferExcludes: Set<BackupRule>,
        val deviceTransferIncludes: Set<BackupRule>,
    )

    private data class FullBackupContent(
        val excludes: Set<BackupRule>,
        val includes: Set<BackupRule>,
    )

    private class DataExtractionRulesBuilder {
        val cloudExcludes = mutableSetOf<BackupRule>()
        val cloudIncludes = mutableSetOf<BackupRule>()
        val deviceExcludes = mutableSetOf<BackupRule>()
        val deviceIncludes = mutableSetOf<BackupRule>()

        fun addRule(
            tagName: String,
            currentSection: String?,
            rule: BackupRule,
        ) {
            val isCloud = currentSection == "cloud-backup"
            val targetExcludes = if (isCloud) cloudExcludes else deviceExcludes
            val targetIncludes = if (isCloud) cloudIncludes else deviceIncludes
            if (tagName == "exclude") {
                targetExcludes.add(rule)
            } else {
                targetIncludes.add(rule)
            }
        }

        fun build() =
            DataExtractionRules(
                cloudBackupExcludes = cloudExcludes,
                cloudBackupIncludes = cloudIncludes,
                deviceTransferExcludes = deviceExcludes,
                deviceTransferIncludes = deviceIncludes,
            )
    }

    private fun parseDataExtractionRules(parser: XmlResourceParser): DataExtractionRules {
        val builder = DataExtractionRulesBuilder()
        var currentSection: String? = null
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            currentSection = processDataExtractionEvent(parser, eventType, currentSection, builder)
            eventType = parser.next()
        }

        return builder.build()
    }

    private fun processDataExtractionEvent(
        parser: XmlResourceParser,
        eventType: Int,
        currentSection: String?,
        builder: DataExtractionRulesBuilder,
    ): String? =
        when (eventType) {
            XmlPullParser.START_TAG -> handleDataExtractionStartTag(parser, currentSection, builder)
            XmlPullParser.END_TAG -> {
                if (parser.name == "cloud-backup" || parser.name == "device-transfer") null else currentSection
            }

            else -> currentSection
        }

    private fun handleDataExtractionStartTag(
        parser: XmlResourceParser,
        currentSection: String?,
        builder: DataExtractionRulesBuilder,
    ): String? =
        when (val tagName = parser.name) {
            "cloud-backup", "device-transfer" -> tagName
            "exclude", "include" -> {
                parser.readBackupRule()?.let { rule ->
                    builder.addRule(tagName, currentSection, rule)
                }
                currentSection
            }

            else -> currentSection
        }

    private fun parseFullBackupContent(parser: XmlResourceParser): FullBackupContent {
        val excludes = mutableSetOf<BackupRule>()
        val includes = mutableSetOf<BackupRule>()
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                processFullBackupStartTag(parser, excludes, includes)
            }
            eventType = parser.next()
        }

        return FullBackupContent(excludes = excludes, includes = includes)
    }

    private fun processFullBackupStartTag(
        parser: XmlResourceParser,
        excludes: MutableSet<BackupRule>,
        includes: MutableSet<BackupRule>,
    ) {
        val tagName = parser.name
        if (tagName == "exclude" || tagName == "include") {
            parser.readBackupRule()?.let { rule ->
                if (tagName == "exclude") excludes.add(rule) else includes.add(rule)
            }
        }
    }

    private fun XmlResourceParser.readBackupRule(): BackupRule? =
        getAttribute("domain")?.let { domain ->
            getAttribute("path")?.let { path ->
                BackupRule(domain, path)
            }
        }

    private fun XmlResourceParser.getAttribute(name: String): String? =
        getAttributeValue(null, name)
            ?: getAttributeValue("", name)
            ?: (0 until attributeCount)
                .firstOrNull { getAttributeName(it) == name }
                ?.let { getAttributeValue(it) }
}
