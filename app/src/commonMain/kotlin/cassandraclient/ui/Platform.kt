package cassandraclient.ui

/** Small preferences that survive restarts (localStorage on the web, a JSON file on desktop). */
expect object Storage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)

    /** Forgotten when the tab/app closes. */
    fun sessionGet(key: String): String?
    fun sessionSet(key: String, value: String?)
}

/** Browser download on the web, a save dialog on desktop. */
expect fun saveTextFile(fileName: String, mimeType: String, text: String)

expect fun copyToClipboard(text: String)

expect fun formatTimestamp(epochMillis: Long): String
