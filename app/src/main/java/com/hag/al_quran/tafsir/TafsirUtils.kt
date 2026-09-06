package com.hag.al_quran.tafsir

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object TafsirUtils {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // تحميل تفسير آية من ملف مخزن محليًا
    fun getAyahTafsir(context: Context, surah: Int, ayah: Int, tafsirFile: String): String? {
        return try {
            val file = File(context.filesDir, tafsirFile)
            if (!file.exists()) {
                Log.e("TafsirUtils", "File not found: $tafsirFile")
                return null
            }

            val tafsirJson = file.readText()
            val tafsirObj = JSONObject(tafsirJson)
            val surahObj = tafsirObj.optJSONObject(surah.toString()) ?: return null

            // الطريقة الأولى: مباشرة (1 -> "النص")
            val direct = surahObj.optString(ayah.toString(), null)
            if (!direct.isNullOrEmpty()) return direct

            // الطريقة الثانية: داخل ayahs (مصفوفة أو كائن مفاتيحه أرقام الآيات)
            if (surahObj.has("ayahs")) {
                when (val ayahs = surahObj.opt("ayahs")) {
                    is JSONArray -> {
                        for (i in 0 until ayahs.length()) {
                            val ayahObj = ayahs.optJSONObject(i) ?: continue
                            if (ayahObj.optInt("ayah") == ayah) {
                                return ayahObj.optString("text", null)
                            }
                        }
                    }
                    is JSONObject -> {
                        val value = ayahs.opt(ayah.toString())
                        if (value is String && value.isNotBlank()) return value
                        if (value is JSONObject) {
                            val text = value.optString("text", null)
                            if (!text.isNullOrBlank()) return text
                        }
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.e("TafsirUtils", "Error loading tafsir: ${e.message}")
            null
        }
    }

    // تحميل ملف التفسير من الإنترنت وتخزينه في FilesDir
    fun downloadTafsirIfNeeded(
        context: Context,
        fileName: String,
        url: String,
        forceDownload: Boolean = false,
        callback: (Boolean, File?) -> Unit
    ) {
        val file = File(context.filesDir, fileName)
        if (file.exists() && !forceDownload && isValidTafsirFile(file)) {
            callback(true, file)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(url).build()
                val partFile = File(context.filesDir, "$fileName.part")
                if (partFile.exists()) partFile.delete()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        callback(false, null)
                        return@launch
                    }

                    val body = response.body ?: return@launch callback(false, null)
                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output -> input.copyTo(output) }
                    }
                }

                if (!isValidTafsirFile(partFile)) {
                    partFile.delete()
                    callback(false, null)
                    return@launch
                }

                partFile.copyTo(file, overwrite = true)
                partFile.delete()

                callback(true, file)
            } catch (e: Exception) {
                Log.e("TafsirDownload", "Download failed: ${e.message}", e)
                callback(false, null)
            }
        }
    }

    private fun isValidTafsirFile(file: File): Boolean {
        if (!file.isFile || file.length() < 10L) return false
        return try {
            JSONObject(file.readText()).length() > 0
        } catch (_: Exception) {
            false
        }
    }
}
