package com.hag.al_quran

import android.os.Bundle
import android.widget.FrameLayout
import androidx.core.view.WindowCompat

/** شاشة مستقلة للإعدادات حتى يعود المستخدم مباشرة إلى صفحة المصحف. */
class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        val container = FrameLayout(this).apply {
            id = R.id.main_content
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(container)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, SettingsFragment())
                .commit()
        }
    }
}
