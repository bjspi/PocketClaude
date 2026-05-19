package de.smartzone.pocketclaude.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Synchronous SharedPreferences-backed locale store. We can't use DataStore
 * here because `attachBaseContext` runs before any coroutine scope exists, and
 * we need the saved value to wrap the base Context.
 *
 * Values:
 *   - empty string  → follow system locale
 *   - "en", "de", "es", "fr", "pt-BR", "zh", "ja" → force that locale
 *
 * Settings-screen language picker writes here; on the next Activity start
 * (or `Activity.recreate()`) the override takes effect.
 */
object LocalePrefs {
    private const val PREFS_NAME = "pocket_claude_locale"
    private const val KEY = "locale_tag"

    /** All locales the app ships translations for. */
    val SUPPORTED = listOf("en", "de", "es", "fr", "pt-BR", "zh", "ja")

    fun get(ctx: Context): String {
        return prefs(ctx).getString(KEY, "") ?: ""
    }

    fun set(ctx: Context, tag: String) {
        prefs(ctx).edit().putString(KEY, tag).apply()
    }

    /**
     * Wrap a Context with the saved locale override. Call this from
     * `Activity.attachBaseContext(newBase)` BEFORE `super.attachBaseContext`.
     *
     * If no override is set, returns the base Context unchanged (system locale wins).
     */
    fun wrap(base: Context): Context {
        val tag = get(base)
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cfg.setLocale(locale)
            val list = android.os.LocaleList(locale)
            android.os.LocaleList.setDefault(list)
            cfg.setLocales(list)
        } else {
            @Suppress("DEPRECATION")
            cfg.locale = locale
        }
        return base.createConfigurationContext(cfg)
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
