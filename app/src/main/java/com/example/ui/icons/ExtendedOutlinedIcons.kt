package androidx.compose.material.icons.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun outlinedIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = "Outlined.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = SolidColor(Color.Black)
        )
    }.build()

private val _deleteOutline: ImageVector by lazy {
    outlinedIcon(
        "DeleteOutline",
        "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6V19zM8,9h8v10H8V9zM15.5,4l-1,-1h-5l-1,1H5v2h14V4H15.5z"
    )
}

private val _folderOpen: ImageVector by lazy {
    outlinedIcon(
        "FolderOpen",
        "M20,6h-8l-2,-2H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C22,6.9 21.1,6 20,6zM20,18H4V8h16V18z"
    )
}

private val _pushPin: ImageVector by lazy {
    outlinedIcon(
        "PushPin",
        "M14,4v5c0,1.12 0.37,2.16 1,3H9c0.65,-0.86 1,-1.9 1,-3V4H14M17,2H7C6.45,2 6,2.45 6,3s0.45,1 1,1h1v5c0,1.66 -1.34,3 -3,3v2h5.97v7l1,1l1,-1v-7H19v-2c-1.66,0 -3,-1.34 -3,-3V4h1c0.55,0 1,-0.45 1,-1S17.55,2 17,2z"
    )
}

private val _subtitles: ImageVector by lazy {
    outlinedIcon(
        "Subtitles",
        "M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4zM20,18H4V6h16V18z" +
            "M6,10h2v2H6V10zM6,14h8v2H6V14zM16,14h2v2h-2V14zM10,10h8v2h-8V10z"
    )
}

val Icons.Outlined.DeleteOutline: ImageVector get() = _deleteOutline
val Icons.Outlined.FolderOpen: ImageVector get() = _folderOpen
val Icons.Outlined.PushPin: ImageVector get() = _pushPin
val Icons.Outlined.Subtitles: ImageVector get() = _subtitles
