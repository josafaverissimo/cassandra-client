package cassandraclient.ui

import cassandraclient.backend.defaultConfigPath
import cassandraclient.core.AppJson
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.text.DateFormat
import java.util.Date
import javax.swing.JOptionPane

private val statePath: Path = defaultConfigPath().resolveSibling("ui-state.json")

actual object Storage {
    private val values: MutableMap<String, String> by lazy {
        runCatching {
            AppJson.decodeFromString<Map<String, String>>(Files.readString(statePath)).toMutableMap()
        }.getOrElse { mutableMapOf() }
    }
    private val session = mutableMapOf<String, String>()

    private fun persist() {
        runCatching {
            statePath.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.writeString(statePath, AppJson.encodeToString<Map<String, String>>(values))
        }
    }

    actual fun get(key: String): String? = values[key]

    actual fun set(key: String, value: String) {
        values[key] = value
        persist()
    }

    actual fun remove(key: String) {
        values.remove(key)
        persist()
    }

    actual fun sessionGet(key: String): String? = session[key]

    actual fun sessionSet(key: String, value: String?) {
        if (value == null) session.remove(key) else session[key] = value
    }
}

actual fun saveTextFile(fileName: String, mimeType: String, text: String) {
    val dialog = FileDialog(null as Frame?, "Save as", FileDialog.SAVE)
    dialog.file = fileName
    dialog.isVisible = true
    val name = dialog.file ?: return
    try {
        Files.writeString(Path.of(dialog.directory, name), text)
    } catch (e: IOException) {
        JOptionPane.showMessageDialog(null, e.message, "Could not save $name", JOptionPane.ERROR_MESSAGE)
    }
}

actual fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

actual fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(epochMillis))
