package cassandraclient.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun icon(name: String, path: String, filled: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(path),
            fill = if (filled) SolidColor(Color.White) else null,
            stroke = if (filled) null else SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()

object Icons {
    val Play = icon("play", "M7 4 L20 12 L7 20 Z", filled = true)
    val Close = icon("close", "M6 6 L18 18 M18 6 L6 18")
    val Refresh = icon("refresh", "M20 11 A8 8 0 1 0 18 17 M20 5 V11 H14")
    val CaretDown = icon("caretDown", "M6 9 L18 9 L12 16 Z", filled = true)
    val CaretRight = icon("caretRight", "M9 6 L16 12 L9 18 Z", filled = true)
    val Plus = icon("plus", "M12 5 V19 M5 12 H19")
    val Edit = icon("edit", "M4 20 L8 19 L19 8 L16 5 L5 16 Z")
    val Expand = icon("expand", "M14 4 H20 V10 M20 4 L13 11 M10 20 H4 V14 M4 20 L11 13")
    val Up = icon("up", "M12 19 V5 M6 11 L12 5 L18 11")
    val Down = icon("down", "M12 5 V19 M6 13 L12 19 L18 13")
    val Filter = icon("filter", "M4 5 H20 L14 13 V19 L10 17 V13 Z")
    val Columns = icon("columns", "M4 6 H20 M4 12 H20 M4 18 H20")
    val Lock = icon("lock", "M6 11 H18 V20 H6 Z M8 11 V8 A4 4 0 0 1 16 8 V11")
    val Server = icon("server", "M4 4 H20 V10 H4 Z M4 14 H20 V20 H4 Z M8 7 H8.01 M8 17 H8.01")
    val Star = icon("star", "M12 3 L14.8 9 L21 9.7 L16.4 14 L17.7 20.5 L12 17.2 L6.3 20.5 L7.6 14 L3 9.7 L9.2 9 Z", filled = true)
    val User = icon("user", "M12 12 A4 4 0 1 0 12 4 A4 4 0 0 0 12 12 Z M5 21 C5 16 19 16 19 21")
    val Users = icon("users", "M9 12 A3.5 3.5 0 1 0 9 5 A3.5 3.5 0 0 0 9 12 Z M3 20 C3 15.5 15 15.5 15 20 M16 5.3 A3.5 3.5 0 0 1 16 11.7 M18 15.5 C20 16 21 17.5 21 20")
    val Warn = icon("warn", "M12 4 L22 20 H2 Z M12 10 V14 M12 17 V17.01")
    val Check = icon("check", "M5 13 L10 18 L19 7")
    val Database = icon("database", "M4 6 A8 3 0 1 0 20 6 A8 3 0 1 0 4 6 Z M4 6 V18 A8 3 0 0 0 20 18 V6 M4 12 A8 3 0 0 0 20 12")
    val Table = icon("table", "M4 5 H20 V19 H4 Z M4 10 H20 M10 10 V19")
    val View = icon("view", "M2 12 C6 5 18 5 22 12 C18 19 6 19 2 12 Z M12 9 A3 3 0 1 0 12 15 A3 3 0 1 0 12 9 Z")
}

@Composable
fun Ico(vector: ImageVector, tint: Color = colors.text, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    Image(vector, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
