package cassandraclient

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cassandraclient.backend.CassandraBackend
import cassandraclient.ui.App
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val NON_REPARENTING_FLAG = "_JAVA_AWT_WM_NONREPARENTING"

/** Compositors that don't reparent windows; on the others (GNOME, KDE...) the flag can misplace things. */
private val NON_REPARENTING_DESKTOPS = setOf("niri", "hyprland", "sway", "river", "dwm", "bspwm", "xmonad")

private fun needsNonReparentingFlag(): Boolean {
    if (System.getenv(NON_REPARENTING_FLAG) != null) return false
    val desktops = listOfNotNull(System.getenv("XDG_CURRENT_DESKTOP"), System.getenv("XDG_SESSION_DESKTOP"))
        .flatMap { it.lowercase().split(':') }
    return desktops.any { it in NON_REPARENTING_DESKTOPS }
}

/**
 * Without the flag AWT ignores the size a tiling compositor assigns, leaving the rest of the window black.
 * AWT reads it from the process environment at startup, so the only way to set it, for `:app:run` and the
 * packaged app alike, is to start over with it. Returns the child's exit code, or null if it couldn't start.
 */
private fun relaunchWithNonReparentingFlag(): Int? {
    val command = ProcessHandle.current().info().command().orElse(null) ?: return null
    return try {
        // Not ProcessHandle's arguments(): the JDK returns none for long command lines, like Gradle's classpath.
        val arguments = Files.readString(Path.of("/proc/self/cmdline")).split('\u0000').drop(1).dropLast(1)
        val child = ProcessBuilder(listOf(command) + arguments)
            .inheritIO()
            .apply {
                environment()[NON_REPARENTING_FLAG] = "1"
                // Marks the second stage of the packaged launcher: inherited, the child would misread its arguments.
                environment().remove("_JPACKAGE_LAUNCHER")
            }
            .start()
        Runtime.getRuntime().addShutdownHook(Thread { child.destroyForcibly() })
        child.waitFor()
    } catch (_: IOException) {
        null
    }
}

fun main() {
    if (needsNonReparentingFlag()) relaunchWithNonReparentingFlag()?.let { exitProcess(it) }

    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    // On desktop the UI talks to the driver directly, with no HTTP server in between.
    val backend = CassandraBackend()
    application {
        Window(
            onCloseRequest = {
                runBlocking { withTimeoutOrNull(3.seconds) { backend.shutdown() } }
                // Not exitApplication(): the JVM can hang forever in its own exit once AWT is up, even ignoring
                // SIGTERM. Settings are written as they change, so nothing is left to flush at this point.
                Runtime.getRuntime().halt(0)
            },
            title = "Cassandra Client",
            state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
        ) {
            App(backend)
        }
    }
}
