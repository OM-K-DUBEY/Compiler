package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class IdeSemanticColors(
  val border: Color,
  val elevatedSurface: Color,
  val success: Color,
  val warning: Color,
  val isDark: Boolean,
)

val LocalIdeColors =
  staticCompositionLocalOf {
    IdeSemanticColors(
      border = IdeBorderDark,
      elevatedSurface = IdeSurfaceElevatedDark,
      success = IdeEmeraldSuccess,
      warning = IdeAmberWarning,
      isDark = true,
    )
  }

private val DevForgeDarkScheme =
  darkColorScheme(
    primary = IdeCyanPrimary,
    onPrimary = Color(0xFF04263A),
    primaryContainer = IdeCyanContainerDark,
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = IdeVioletSecondary,
    onSecondary = Color(0xFF1E1038),
    secondaryContainer = Color(0xFF2E1F47),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = IdeEmeraldSuccess,
    onTertiary = Color(0xFF022C22),
    background = IdeBgDark,
    onBackground = IdeTextPrimaryDark,
    surface = IdeSurfaceDark,
    onSurface = IdeTextPrimaryDark,
    surfaceVariant = IdeSurfaceVariantDark,
    onSurfaceVariant = IdeTextSecondaryDark,
    outline = IdeBorderDark,
    outlineVariant = Color(0xFF21262D),
    error = IdeCoralError,
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF3F1518),
    onErrorContainer = Color(0xFFFECACA),
  )

private val DevForgeLightScheme =
  lightColorScheme(
    primary = IdeBluePrimaryLight,
    onPrimary = Color.White,
    primaryContainer = IdeBlueContainerLight,
    onPrimaryContainer = Color(0xFF075985),
    secondary = IdeVioletSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF4C1D95),
    tertiary = IdeEmeraldLight,
    onTertiary = Color.White,
    background = IdeBgLight,
    onBackground = IdeTextPrimaryLight,
    surface = IdeSurfaceLight,
    onSurface = IdeTextPrimaryLight,
    surfaceVariant = IdeSurfaceVariantLight,
    onSurfaceVariant = IdeTextSecondaryLight,
    outline = IdeBorderLight,
    outlineVariant = Color(0xFFE5E7EB),
    error = IdeErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
  )

private val DevForgeShapes =
  Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
  )

@Composable
fun DevForgeTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DevForgeDarkScheme else DevForgeLightScheme
  val ideColors =
    if (darkTheme) {
      IdeSemanticColors(
        border = IdeBorderDark,
        elevatedSurface = IdeSurfaceElevatedDark,
        success = IdeEmeraldSuccess,
        warning = IdeAmberWarning,
        isDark = true,
      )
    } else {
      IdeSemanticColors(
        border = IdeBorderLight,
        elevatedSurface = IdeSurfaceVariantLight,
        success = IdeEmeraldLight,
        warning = Color(0xFFD97706),
        isDark = false,
      )
    }

  CompositionLocalProvider(LocalIdeColors provides ideColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = DevForgeTypography,
      shapes = DevForgeShapes,
      content = content,
    )
  }
}
