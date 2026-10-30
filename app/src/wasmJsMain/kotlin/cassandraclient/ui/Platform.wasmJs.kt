@file:OptIn(ExperimentalWasmJsInterop::class)

package cassandraclient.ui

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

// Storage can be blocked (private windows, site settings); the app then just doesn't remember anything.
private fun localGet(key: String): String? = js("(() => { try { return localStorage.getItem(key); } catch (e) { return null; } })()")
private fun localSet(key: String, value: String) { js("(() => { try { localStorage.setItem(key, value); } catch (e) {} })()") }
private fun localRemove(key: String) { js("(() => { try { localStorage.removeItem(key); } catch (e) {} })()") }
private fun sessionRead(key: String): String? = js("(() => { try { return sessionStorage.getItem(key); } catch (e) { return null; } })()")
private fun sessionWrite(key: String, value: String) { js("(() => { try { sessionStorage.setItem(key, value); } catch (e) {} })()") }
private fun sessionDelete(key: String) { js("(() => { try { sessionStorage.removeItem(key); } catch (e) {} })()") }

actual object Storage {
    actual fun get(key: String): String? = localGet(key)
    actual fun set(key: String, value: String) = localSet(key, value)
    actual fun remove(key: String) = localRemove(key)
    actual fun sessionGet(key: String): String? = sessionRead(key)
    actual fun sessionSet(key: String, value: String?) {
        if (value == null) sessionDelete(key) else sessionWrite(key, value)
    }
}

private fun downloadBlob(fileName: String, mimeType: String, text: String) {
    js(
        """
        const a = document.createElement('a');
        a.href = URL.createObjectURL(new Blob([text], { type: mimeType }));
        a.download = fileName;
        a.click();
        URL.revokeObjectURL(a.href);
        """,
    )
}

actual fun saveTextFile(fileName: String, mimeType: String, text: String) = downloadBlob(fileName, mimeType, text)

private fun writeClipboard(text: String) {
    js("navigator.clipboard && navigator.clipboard.writeText(text)")
}

actual fun copyToClipboard(text: String) = writeClipboard(text)

private fun localeDate(epochMillis: Double): String = js("new Date(epochMillis).toLocaleString()")

actual fun formatTimestamp(epochMillis: Long): String = localeDate(epochMillis.toDouble())
