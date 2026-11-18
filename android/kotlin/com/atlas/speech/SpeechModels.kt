package com.atlas.speech

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * The speech models on the guide site's Downloads page. Each is a sherpa-onnx archive from GitHub, which
 * Atlas checks against its size and SHA-256, then unpacks into its own files.
 */
object SpeechModels {
    enum class Kind { Canary, Transducer }

    class Model(
        val id: String,
        val name: String,
        val kind: Kind,
        val bytes: Long,
        val sha256: String,
        /** The languages Canary can be told to listen for. Other models find the language themselves. */
        val languages: Set<String> = emptySet(),
    ) {
        val files get() = when (kind) {
            Kind.Canary -> listOf(ENCODER, DECODER, TOKENS)
            Kind.Transducer -> listOf(ENCODER, DECODER, JOINER, TOKENS)
        }
    }

    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"

    val ALL = listOf(
        Model("canary-180m", "Canary 180M Flash", Kind.Canary, 153_692_328L,
            "7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9", setOf("en", "de", "es", "fr")),
        Model("parakeet-english", "Parakeet Unified English 0.6B", Kind.Transducer, 501_350_460L,
            "99f63605b3a85a54c250c0869670a687b7d6598a47bf2421515e1f839a76e150"),
        Model("parakeet-ultra", "Moondream Parakeet Ultra", Kind.Transducer, 487_134_290L,
            "d216b71d7f628b3f2f2a20ee7ead39ff0b075984959eb4bf6ddf8dc8da737c09"),
    )

    private fun preferences(context: Context) = context.getSharedPreferences("speech", Context.MODE_PRIVATE)

    fun enabled(context: Context) = preferences(context).getBoolean("enabled", false)

    fun enabled(context: Context, on: Boolean) {
        preferences(context).edit().putBoolean("enabled", on).apply()
    }

    fun folder(context: Context, model: Model) = File(context.filesDir, "speech/${model.id}")

    fun installed(context: Context, model: Model) = model.files.all { File(folder(context, model), it).isFile }

    /** The chosen model, or the first one added when the choice was never made or was removed. */
    fun chosen(context: Context): Model? {
        val id = preferences(context).getString("model", null)
        return ALL.firstOrNull { it.id == id && installed(context, it) } ?: ALL.firstOrNull { installed(context, it) }
    }

    fun choose(context: Context, id: String) {
        require(ALL.any { it.id == id && installed(context, it) }) { "That model has not been added." }
        preferences(context).edit().putString("model", id).apply()
    }

    /**
     * Adds a downloaded archive and chooses it. Throws with a message for people when the file is not one of
     * the models, so nothing is kept from a wrong or broken download. Runs for a minute or more, so call it
     * off the main thread.
     */
    internal fun add(context: Context, uri: Uri, opened: () -> Unit, progress: (Int) -> Unit): Model {
        val resolver = context.contentResolver
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        val model = ALL.firstOrNull { it.bytes == size } ?: throw IOException(NOT_A_MODEL)
        val folder = folder(context, model)
        val unpacking = File(folder.parentFile, "${model.id}.unpacking")
        unpacking.deleteRecursively()
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException(NOT_A_MODEL)
            input.use {
                opened()
                unpack(model, it, unpacking, progress)
            }
            folder.deleteRecursively()
            if (!unpacking.renameTo(folder)) throw IOException("Could not save the model.")
        } finally {
            unpacking.deleteRecursively()
        }
        choose(context, model.id)
        return model
    }

    /** Keeps the model's files from the archive, and checks that every byte matches the download page. */
    private fun unpack(model: Model, input: InputStream, into: File, progress: (Int) -> Unit) {
        if (!into.mkdirs()) throw IOException("Could not save the model.")
        val digest = MessageDigest.getInstance("SHA-256")
        val checked = DigestInputStream(input.buffered(1 shl 16), digest)
        var read = 0L
        var nextReport = model.bytes / 100
        val counted = object : java.io.FilterInputStream(checked) {
            private fun count(bytes: Int) {
                read += bytes
                if (read >= nextReport) {
                    val percent = (read * 100 / model.bytes).toInt().coerceIn(1, 99)
                    nextReport = if (percent == 99) Long.MAX_VALUE else (percent + 1) * model.bytes / 100 + 1
                    progress(percent)
                }
            }

            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) count(it) }
        }
        try {
            val tar = TarArchiveInputStream(BZip2CompressorInputStream(counted, false))
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in model.files) continue
                File(into, name).outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        } catch (error: IOException) {
            throw IOException(NOT_A_MODEL, error)
        }
        // The archive can end before the file does, and every byte counts towards the checksum.
        val rest = ByteArray(1 shl 16)
        while (counted.read(rest) >= 0) Unit
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (read != model.bytes || hex != model.sha256 || model.files.any { !File(into, it).isFile }) throw IOException(NOT_A_MODEL)
    }

    private const val NOT_A_MODEL = "Choose a file from the speech to text page."
}
