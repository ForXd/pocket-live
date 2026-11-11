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
    primary=Color(0xffff5364),onPrimary=Color(0xff280008),primaryContainer=Color(0xff49151e),onPrimaryContainer=Color(0xffffd9de),
    secondary=Color(0xffd5c2c5),onSecondary=Color(0xff372d30),secondaryContainer=Color(0xff35282b),onSecondaryContainer=Color(0xffeedde0),
    tertiary=Color(0xffe9c178),onTertiary=Color(0xff402d06),tertiaryContainer=Color(0xff58431d),onTertiaryContainer=Color(0xffffe4ac),
    background=Color(0xff0c0c0e),onBackground=Color(0xfff5f3f4),surface=Color(0xff0c0c0e),onSurface=Color(0xfff5f3f4),
    surfaceVariant=Color(0xff353135),onSurfaceVariant=Color(0xffbcb5b9),surfaceTint=Color(0xffff5364),
    surfaceContainerLowest=Color(0xff080809),surfaceContainerLow=Color(0xff141416),surfaceContainer=Color(0xff1b1b1e),
    surfaceContainerHigh=Color(0xff242427),surfaceContainerHighest=Color(0xff303034),surfaceDim=Color(0xff0c0c0e),surfaceBright=Color(0xff38383c),
    outline=Color(0xff978d92),outlineVariant=Color(0xff373236))
internal val DayColors=lightColorScheme(
    primary=Color(0xffbb1735),onPrimary=Color.White,primaryContainer=Color(0xffffdde3),onPrimaryContainer=Color(0xff860f25),
    secondary=Color(0xff6e575e),onSecondary=Color.White,secondaryContainer=Color(0xfff0e4e7),onSecondaryContainer=Color(0xff49353c),
    tertiary=Color(0xff765720),onTertiary=Color.White,tertiaryContainer=Color(0xffffe8bb),onTertiaryContainer=Color(0xff513a10),
    background=Color(0xfffaf7f8),onBackground=Color(0xff211e20),surface=Color(0xfffaf7f8),onSurface=Color(0xff211e20),
    surfaceVariant=Color(0xffeee5e8),onSurfaceVariant=Color(0xff6c6268),surfaceTint=Color(0xffbb1735),
    surfaceContainerLowest=Color(0xffffffff),surfaceContainerLow=Color(0xfff5eff1),surfaceContainer=Color(0xfff0e9ec),
    surfaceContainerHigh=Color(0xffeae2e6),surfaceContainerHighest=Color(0xffe5dce1),surfaceDim=Color(0xffded5da),surfaceBright=Color(0xfffffbfc),
    outline=Color(0xff897b83),outlineVariant=Color(0xffd9cbd2))

@Composable fun PocketTheme(mode:ThemeMode?=null,content:@Composable ()->Unit) {
    val stored=rememberThemeMode()
    val dark=when(mode ?: stored) { ThemeMode.SYSTEM->isSystemInDarkTheme();ThemeMode.LIGHT->false;ThemeMode.DARK->true }
    val activity=androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity
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
