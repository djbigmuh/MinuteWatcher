package com.example.minutewatcher

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * Verifies consistency and completeness of cloud backup and device transfer configuration rules.
 */
@RunWith(RobolectricTestRunner::class)
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

    private fun parseDataExtractionRules(parser: XmlResourceParser): DataExtractionRules {
        val cloudExcludes = mutableSetOf<BackupRule>()
        val cloudIncludes = mutableSetOf<BackupRule>()
        val deviceExcludes = mutableSetOf<BackupRule>()
        val deviceIncludes = mutableSetOf<BackupRule>()

        var currentSection: String? = null
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (val tagName = parser.name) {
                        "cloud-backup", "device-transfer" -> {
                            currentSection = tagName
                        }

                        "exclude", "include" -> {
                            val domain = parser.getAttribute("domain")
                            val path = parser.getAttribute("path")
                            if ((domain != null) && (path != null)) {
                                val rule = BackupRule(domain, path)
                                val targetExcludes =
                                    if (currentSection ==
                                        "cloud-backup"
                                    ) {
                                        cloudExcludes
                                    } else {
                                        deviceExcludes
                                    }
                                val targetIncludes =
                                    if (currentSection ==
                                        "cloud-backup"
                                    ) {
                                        cloudIncludes
                                    } else {
                                        deviceIncludes
                                    }
                                if (tagName == "exclude") {
                                    targetExcludes.add(rule)
                                } else {
                                    targetIncludes.add(rule)
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if ((parser.name == "cloud-backup") || (parser.name == "device-transfer")) {
                        currentSection = null
                    }
                }
            }
            eventType = parser.next()
        }

        return DataExtractionRules(
            cloudBackupExcludes = cloudExcludes,
            cloudBackupIncludes = cloudIncludes,
            deviceTransferExcludes = deviceExcludes,
            deviceTransferIncludes = deviceIncludes,
        )
    }

    private fun parseFullBackupContent(parser: XmlResourceParser): FullBackupContent {
        val excludes = mutableSetOf<BackupRule>()
        val includes = mutableSetOf<BackupRule>()

        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name
                if ((tagName == "exclude") || (tagName == "include")) {
                    val domain = parser.getAttribute("domain")
                    val path = parser.getAttribute("path")
                    if ((domain != null) && (path != null)) {
                        val rule = BackupRule(domain, path)
                        if (tagName == "exclude") excludes.add(rule) else includes.add(rule)
                    }
                }
            }
            eventType = parser.next()
        }

        return FullBackupContent(excludes = excludes, includes = includes)
    }

    private fun XmlResourceParser.getAttribute(name: String): String? {
        val valueFromNullNs = getAttributeValue(null, name)
        if (valueFromNullNs != null) return valueFromNullNs

        val valueFromEmptyNs = getAttributeValue("", name)
        if (valueFromEmptyNs != null) return valueFromEmptyNs

        for (i in 0 until attributeCount) {
            if (getAttributeName(i) == name) {
                return getAttributeValue(i)
            }
        }
        return null
    }
}
