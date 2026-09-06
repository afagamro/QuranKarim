package com.hag.al_quran.search

import android.content.Context
import androidx.appcompat.app.AlertDialog

object SmartStatsDialog {

    /**
     * تعريف نموذج البيانات هنا لفك الارتباط بأي كلاسات أخرى (مثل WordIndex القديم)
     * وجعل الـ Dialog قابلاً للاستخدام من أي مكان في التطبيق بسهولة.
     */
    data class SurahBreakdown(
        val surah: Int,
        val totalOccurrences: Int,
        val ayahMatches: Int
    )

    fun show(
        context: Context,
        query: String,
        breakdown: List<SurahBreakdown>,
        surahNames: List<String>
    ) {
        if (breakdown.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("التوزيع على السور")
                .setMessage("لا توجد بيانات لعرضها.")
                .setPositiveButton("حسناً", null)
                .show()
            return
        }

        val lines = breakdown.map { b ->
            val name = surahNames.getOrNull(b.surah - 1) ?: "سورة ${b.surah}"
            // استخدام التنسيق العربي للأرقام
            "• $name — مرات الذكر: ${toArabic(b.totalOccurrences)} — في ${toArabic(b.ayahMatches)} آية"
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("«$query» — التوزيع على السور")
            .setItems(lines, null)
            .setPositiveButton("إغلاق", null)
            .show()
    }

    /**
     * تحويل الأرقام الإنجليزية إلى أرقام عربية (هندية)
     */
    private fun toArabic(n: Int): String {
        val d = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
        return n.toString().map { d[it - '0'] }.joinToString("")
    }
}