package com.hag.al_quran

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.hag.al_quran.Juz.JuzListFragment
import com.hag.al_quran.Surah.SurahListFragment

class HomePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> SurahListFragment()      // تأكد أن هذا الملف موجود ويعمل
            1 -> JuzListFragment()        // تأكد أن هذا الملف موجود ويعمل
            2 -> FavoritesFragment()
            else -> Fragment()
        }
    }
}
