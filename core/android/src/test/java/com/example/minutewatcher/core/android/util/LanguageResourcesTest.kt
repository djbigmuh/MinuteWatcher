package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LanguageResourcesTest {
    @Test
    fun languageArrays_haveSameSize() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = context.resources

        val names = resources.getStringArray(R.array.languages)
        val codes = resources.getStringArray(R.array.language_codes)
        val icons = resources.getStringArray(R.array.language_icons)

        assertEquals("languages and language_codes must have same size", names.size, codes.size)
        assertEquals("languages and language_icons must have same size", names.size, icons.size)
    }
}
