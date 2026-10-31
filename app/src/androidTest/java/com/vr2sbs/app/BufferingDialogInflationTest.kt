package com.vr2sbs.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BufferingDialogInflationTest {
    @Test
    fun bufferingDialogInflatesWithDialogTheme() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = createBufferingDialog(activity)
                dialog.show()
                assertTrue(dialog.isShowing)
                dialog.dismiss()
            }
        }
    }
}
