package cassandraclient

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import cassandraclient.ui.App

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("root") {
        App(HttpCassandraApi())
    }
}
