package androidx.compose.material.icons.automirrored.rounded

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun autoMirroredIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = "AutoMirrored.Rounded.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = true
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = SolidColor(Color.Black)
        )
    }.build()

private val _logout: ImageVector by lazy {
    autoMirroredIcon(
        "Logout",
        "M5,5h6c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h6c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1H5V5z" +
            "M20.65,11.65l-2.79,-2.79C17.54,8.54 17,8.76 17,9.21V11h-7c-0.55,0 -1,0.45 -1,1s0.45,1 1,1h7v1.79c0,0.45 0.54,0.67 0.85,0.35l2.79,-2.79C20.84,12.16 20.84,11.84 20.65,11.65z"
    )
}

private val _volumeOff: ImageVector by lazy {
    autoMirroredIcon(
        "VolumeOff",
        "M3.63,3.63c-0.39,0.39 -0.39,1.02 0,1.41L7.29,8.7L7,9H4c-0.55,0 -1,0.45 -1,1v4c0,0.55 0.45,1 1,1h3l3.29,3.29c0.63,0.63 1.71,0.18 1.71,-0.71v-4.17l4.18,4.18c-0.49,0.37 -1.02,0.68 -1.6,0.91c-0.36,0.15 -0.58,0.53 -0.58,0.92c0,0.72 0.73,1.18 1.39,0.91c0.8,-0.33 1.55,-0.77 2.22,-1.31l1.34,1.34c0.39,0.39 1.02,0.39 1.41,0c0.39,-0.39 0.39,-1.02 0,-1.41L5.05,3.63C4.66,3.24 4.03,3.24 3.63,3.63z" +
            "M19,12c0,0.82 -0.15,1.61 -0.41,2.34l1.53,1.53C20.68,14.7 21,13.39 21,12c0,-3.83 -2.4,-7.11 -5.78,-8.4c-0.59,-0.23 -1.22,0.23 -1.22,0.86c0,0.39 0.25,0.73 0.61,0.87C17.18,6.54 19,9.06 19,12z" +
            "M10.29,5.71L10.12,5.88L12,7.76V6.41C12,5.52 10.92,5.08 10.29,5.71z" +
            "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v1.79l2.48,2.48C16.49,12.16 16.5,12.08 16.5,12z"
    )
}

private val _volumeUp: ImageVector by lazy {
    autoMirroredIcon(
        "VolumeUp",
        "M3,10v4c0,0.55 0.45,1 1,1h3l3.29,3.29c0.63,0.63 1.71,0.18 1.71,-0.71V6.41c0,-0.89 -1.08,-1.34 -1.71,-0.71L7,9H4C3.45,9 3,9.45 3,10z" +
            "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05C15.48,15.29 16.5,13.77 16.5,12z" +
            "M14,4.45v0.2c0,0.38 0.25,0.71 0.6,0.85C17.18,6.53 19,9.06 19,12s-1.82,5.47 -4.4,6.5c-0.36,0.14 -0.6,0.47 -0.6,0.85v0.2c0,0.63 0.63,1.07 1.21,0.85C18.6,19.11 21,15.84 21,12s-2.4,-7.11 -5.79,-8.4C14.63,3.38 14,3.82 14,4.45z"
    )
}

val Icons.AutoMirrored.Rounded.Logout: ImageVector get() = _logout
val Icons.AutoMirrored.Rounded.VolumeOff: ImageVector get() = _volumeOff
val Icons.AutoMirrored.Rounded.VolumeUp: ImageVector get() = _volumeUp
