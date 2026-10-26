// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ThemeMode(val title:String,val description:String) {
    SYSTEM("跟随系统","随手机的日间与夜间设置切换"),LIGHT("日间模式","明亮底色，清晰柔和"),DARK("夜间模式","深色底色，适合暗光观看")
}
object ThemePreference {
    private const val KEY="theme_mode"
    fun preferences(context:Context)=context.applicationContext.getSharedPreferences("appearance",Context.MODE_PRIVATE)
    fun read(context:Context)=runCatching { ThemeMode.valueOf(preferences(context).getString(KEY,ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)
    fun write(context:Context,mode:ThemeMode) { preferences(context).edit().putString(KEY,mode.name).apply() }
}
val LocalPocketDark=staticCompositionLocalOf { true }
@Composable fun rememberThemeMode():ThemeMode {
    val context=LocalContext.current.applicationContext
    var mode by remember { mutableStateOf(ThemePreference.read(context)) }
    DisposableEffect(context) {
        val prefs=ThemePreference.preferences(context)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> mode=ThemePreference.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return mode
}
internal val NightColors=darkColorScheme(
    primary=Color(0xff78d8c6),onPrimary=Color(0xff00382f),primaryContainer=Color(0xff154d43),onPrimaryContainer=Color(0xffa4f3df),
    secondary=Color(0xffb5c8c4),onSecondary=Color(0xff203532),secondaryContainer=Color(0xff304741),onSecondaryContainer=Color(0xffd0e7df),
    tertiary=Color(0xffb2c7f1),onTertiary=Color(0xff1a3152),tertiaryContainer=Color(0xff334969),onTertiaryContainer=Color(0xffd5e3ff),
    background=Color(0xff0e151b),onBackground=Color(0xffe3e9ee),surface=Color(0xff0e151b),onSurface=Color(0xffe3e9ee),
    surfaceVariant=Color(0xff35444a),onSurfaceVariant=Color(0xffb5c2c9),surfaceTint=Color(0xff78d8c6),
    surfaceContainerLowest=Color(0xff090f14),surfaceContainerLow=Color(0xff131d25),surfaceContainer=Color(0xff18232c),
    surfaceContainerHigh=Color(0xff222f39),surfaceContainerHighest=Color(0xff2c3a44),surfaceDim=Color(0xff0e151b),surfaceBright=Color(0xff34424c),
    outline=Color(0xff81929b),outlineVariant=Color(0xff34434d))
private val DayColors=lightColorScheme(
    primary=Color(0xff006b5e),onPrimary=Color.White,primaryContainer=Color(0xffd4f3e9),onPrimaryContainer=Color(0xff004e42),
    secondary=Color(0xff50635e),onSecondary=Color.White,secondaryContainer=Color(0xffe0ebe6),onSecondaryContainer=Color(0xff203831),
    tertiary=Color(0xff435e86),onTertiary=Color.White,tertiaryContainer=Color(0xffdbe7ff),onTertiaryContainer=Color(0xff223c60),
    background=Color(0xfff4f7fa),onBackground=Color(0xff17242d),surface=Color(0xfff4f7fa),onSurface=Color(0xff17242d),
    surfaceVariant=Color(0xffdfe7eb),onSurfaceVariant=Color(0xff52636e),surfaceTint=Color(0xff006b5e),
    surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xffeff3f7),surfaceContainer=Color.White,
    surfaceContainerHigh=Color(0xffe8eef3),surfaceContainerHighest=Color(0xffdfe7ed),surfaceDim=Color(0xffdce4eb),surfaceBright=Color(0xfff9fbfd),
    outline=Color(0xff71828c),outlineVariant=Color(0xffc9d5dd))

@Composable fun PocketTheme(mode:ThemeMode?=null,content:@Composable ()->Unit) {
    val stored=rememberThemeMode()
    val dark=when(mode ?: stored) { ThemeMode.SYSTEM->isSystemInDarkTheme();ThemeMode.LIGHT->false;ThemeMode.DARK->true }
    val activity=LocalContext.current as? androidx.activity.ComponentActivity
    SideEffect { activity?.let {
        WindowCompat.getInsetsController(it.window,it.window.decorView).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark }
    } }
    CompositionLocalProvider(LocalPocketDark provides dark) {
        MaterialTheme(colorScheme=if(dark) NightColors else DayColors,
            shapes=Shapes(extraSmall=RoundedCornerShape(6.dp),small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(14.dp),large=RoundedCornerShape(20.dp),extraLarge=RoundedCornerShape(28.dp)),
            typography=Typography(
                headlineLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=32.sp,lineHeight=40.sp,letterSpacing=(-0.6).sp),
                headlineSmall=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=26.sp,lineHeight=34.sp,letterSpacing=(-0.3).sp),
                titleLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=24.sp,lineHeight=32.sp),
                titleMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=17.sp,lineHeight=25.sp),
                titleSmall=TextStyle(fontWeight=FontWeight.Medium,fontSize=15.sp,lineHeight=22.sp)),content=content)
    }
}
@Composable fun AppearanceDialog(close:()->Unit) {
    val context=LocalContext.current
    val mode=rememberThemeMode()
    AlertDialog(onDismissRequest=close,title={Text("外观")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("选择适合你的观看环境",color=MaterialTheme.colorScheme.onSurfaceVariant)
        ThemeMode.entries.forEach { choice ->
            Surface(shape=MaterialTheme.shapes.medium,color=if(mode==choice) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().selectable(mode==choice,role=Role.RadioButton,onClick={ThemePreference.write(context,choice)}).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected=mode==choice,onClick=null)
                    Column { Text(choice.title,style=MaterialTheme.typography.titleSmall);Text(choice.description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }},confirmButton={TextButton(onClick=close) { Text("完成") }})
}
