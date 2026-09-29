/*
 * Copyright (C) 2025 Fleey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.fleey.ppocrv5.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
  primary = CallAccent,
  onPrimary = CallOnAccent,
  primaryContainer = Color(0xFF303E78),
  onPrimaryContainer = CallAccent,
  secondary = Color(0xFFB6F2C5),
  onSecondary = Color(0xFF12391F),
  secondaryContainer = Color(0xFF214D30),
  onSecondaryContainer = Color(0xFFB6F2C5),
  tertiary = Color(0xFFFFD8A7),
  onTertiary = Color(0xFF4A2A00),
  tertiaryContainer = Color(0xFF704900),
  onTertiaryContainer = Color(0xFFFFD8A7),
  error = Color(0xFFEF9A9A),
  onError = Color(0xFFB71C1C),
  errorContainer = Color(0xFFC62828),
  onErrorContainer = Color(0xFFFFCDD2),
  background = CallBackground,
  onBackground = CallText,
  surface = CallSurface,
  onSurface = CallText,
  surfaceVariant = CallSurfaceVariant,
  onSurfaceVariant = CallMutedText,
  outline = Color(0xFF8D9099),
  outlineVariant = Color(0xFF45474E),
)

@Composable
fun PPOCRv5AndroidTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content,
  )
}
