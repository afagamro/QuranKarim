// File: app/src/main/java/com/hag/al_quran/HomeTabsFragment.kt
package com.hag.al_quran

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class HomeTabsFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // ✅ اضبط اللغة بطريقة حديثة بدون استخدام أي API مُهملة
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("lang", "ar") ?: "ar"

        val newLocales = LocaleListCompat.forLanguageTags(lang)
        if (AppCompatDelegate.getApplicationLocales() != newLocales) {
            AppCompatDelegate.setApplicationLocales(newLocales)
            // لا حاجة لاستدعاء updateConfiguration أو setLayoutDirection
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home_tabs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewPager = view.findViewById(R.id.viewPager)
        tabLayout = view.findViewById(R.id.tabLayout)

        val adapter = HomePagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_surahs)
                1 -> getString(R.string.tab_juz)
                2 -> getString(R.string.tab_favorites)
                else -> "📚"
            }
        }.attach()
    }
}
