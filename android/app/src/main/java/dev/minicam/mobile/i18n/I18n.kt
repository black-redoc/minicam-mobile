package dev.minicam.mobile.i18n

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

// App language (docs/adr/0017): the English text in the code is the key, and the Spanish
// dictionary (Spanish.kt) holds its translation. A key with no entry shows in English.

/** The user's choice in Settings; [SYSTEM] follows the phone's language. */
enum class AppLanguage(val code: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    SPANISH("es"),
    ;

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

object I18n {
    private const val PREFS = "minicam_settings"
    private const val KEY = "language"

    /** Compose state: every text built with [tr] while composing follows a change at once. */
    var language by mutableStateOf(AppLanguage.SYSTEM)
        private set

    /** The phone's own locale, kept before [apply] overrides the process default. */
    private val systemLocale: Locale by lazy {
        // Plain JVM unit tests have no Android resources.
        runCatching { Resources.getSystem().configuration.locales[0] }.getOrNull() ?: Locale.getDefault()
    }

    /** Loads the saved choice; call once when the process starts. */
    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        apply(AppLanguage.fromCode(saved))
    }

    fun select(context: Context, choice: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.code).apply()
        apply(choice)
    }

    /** Uses [choice] without saving it (the saved one is loaded by [init]). */
    internal fun apply(choice: AppLanguage) {
        language = choice
        // Dates and numbers (recording days, times) follow the chosen language too.
        Locale.setDefault(locale)
    }

    /** The locale texts and formats use now. */
    val locale: Locale
        get() = when (language) {
            AppLanguage.SYSTEM -> systemLocale
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.SPANISH -> Locale.forLanguageTag("es")
        }

    val isSpanish: Boolean get() = locale.language == "es"
}

/**
 * [text] in the app language. With [args], [text] is a `String.format` pattern (`%s`, `%d`,
 * `%1$s`) filled after translating, so the translation can reorder them.
 */
fun tr(text: String, vararg args: Any?): String {
    val pattern = if (I18n.isSpanish) Spanish.texts[text] ?: text else text
    return if (args.isEmpty()) pattern else String.format(I18n.locale, pattern, *args)
}
