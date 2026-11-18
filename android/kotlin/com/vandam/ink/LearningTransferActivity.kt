package com.vandam.ink

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.Gravity
import android.widget.TextView
import com.atlas.engine.SwipeTyping
import com.atlas.speech.SpeechModelImport

/**
 * Owns the Android file picker's result and keeps its document grant alive during the transfer. It exports or
 * imports learned words, or adds a downloaded language or speech model.
 */
class LearningTransferActivity : Activity() {
    private val exporting get() = intent.getBooleanExtra(EXPORTING, false)
    private val language get() = intent.getBooleanExtra(LANGUAGE, false)
    private val speech get() = intent.getBooleanExtra(SPEECH, false)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (speech) setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        super.onCreate(savedInstanceState)
        if (!speech) setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            text = if (language) "Adding language…" else if (exporting) "Exporting words…" else "Importing words…"
        })
        if (savedInstanceState != null) return
        val picker = Intent(if (exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (exporting) "application/json" else "*/*")
        if (exporting) picker.putExtra(Intent.EXTRA_TITLE, "atlas-words.json")
        try {
            startActivityForResult(picker, PICK_DOCUMENT)
        } catch (_: ActivityNotFoundException) {
            complete(false, "Could not open the Android file picker.")
        }
    }

    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_DOCUMENT) return
        if (resultCode != RESULT_OK) {
            complete(false)
            return
        }
        val uri = data?.data
        if (uri == null) {
            complete(false, "Could not open the selected file.")
            return
        }
        if (speech) {
            if (!SpeechModelImport.start(applicationContext, uri) { complete(true) }) complete(false)
            return
        }
        val typing = SwipeTyping.get(this)
        if (language) typing.addLanguage(uri) { complete(it == null, it) }
        else if (exporting) typing.exportConfig(uri) { complete(it == null, it) }
        else typing.importConfig(uri) { complete(it == null, it) }
    }

    private fun complete(transferred: Boolean, error: String? = null) {
        val result = Bundle().apply { putString(ERROR, error) }
        intent.getParcelableExtra(RECEIVER, ResultReceiver::class.java)?.send(
            if (transferred) RESULT_OK else RESULT_CANCELED, result,
        )
        finish()
    }

    companion object {
        private const val PICK_DOCUMENT = 1
        private const val RECEIVER = "receiver"
        private const val EXPORTING = "exporting"
        private const val LANGUAGE = "language"
        private const val SPEECH = "speech"
        private const val ERROR = "error"

        /** [language] picks a downloaded language file instead of a backup, and [speech] a speech model. */
        internal fun start(activity: Activity, exporting: Boolean, complete: (Boolean, String?) -> Unit, language: Boolean = false,
                           speech: Boolean = false) {
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    complete(resultCode == RESULT_OK, resultData?.getString(ERROR))
                }
            }
            activity.startActivity(Intent(activity, LearningTransferActivity::class.java)
                .putExtra(EXPORTING, exporting).putExtra(LANGUAGE, language).putExtra(SPEECH, speech).putExtra(RECEIVER, receiver))
        }
    }
}
