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
    primary=Color.White,onPrimary=Color(0xff232323),primaryContainer=Color(0xff373737),onPrimaryContainer=Color(0xffececec),
    secondary=Color(0xffc3c3c3),onSecondary=Color(0xff2c2c2c),secondaryContainer=Color(0xff353535),onSecondaryContainer=Color(0xffe6e6e6),
    tertiary=Color(0xffb7b7b7),onTertiary=Color(0xff2c2c2c),
    background=Color(0xff1b1b1b),onBackground=Color(0xfff0f0f0),surface=Color(0xff1b1b1b),onSurface=Color(0xfff0f0f0),
    surfaceVariant=Color(0xff313131),onSurfaceVariant=Color(0xffb6b6b6),surfaceTint=Color(0xffe4e4e4),
    surfaceContainerLowest=Color(0xff121212),surfaceContainerLow=Color(0xff232323),surfaceContainer=Color(0xff2e2e2e),
    surfaceContainerHigh=Color(0xff353535),surfaceContainerHighest=Color(0xff414141),surfaceDim=Color(0xff1b1b1b),surfaceBright=Color(0xff3b3b3b),
    outline=Color(0xff8b8b8b),outlineVariant=Color(0xff444444))
internal val DayColors=lightColorScheme(
    primary=Color(0xff171717),onPrimary=Color.White,primaryContainer=Color(0xffebebeb),onPrimaryContainer=Color(0xff333333),
    secondary=Color(0xff555555),onSecondary=Color.White,secondaryContainer=Color(0xfff2f2f2),onSecondaryContainer=Color.Black,
    tertiary=Color(0xff555555),onTertiary=Color.White,
    background=Color(0xfff5f5f5),onBackground=Color(0xff252525),surface=Color.White,onSurface=Color(0xff252525),
    surfaceVariant=Color(0xfff2f2f2),onSurfaceVariant=Color(0xff5f5f5f),surfaceTint=Color.Transparent,
    surfaceContainerLowest=Color.White,surfaceContainerLow=Color.White,surfaceContainer=Color.White,
    surfaceContainerHigh=Color(0xfff5f5f5),surfaceContainerHighest=Color(0xffeeeeee),surfaceDim=Color(0xfff5f5f5),surfaceBright=Color.White,
    outline=Color(0xff808080),outlineVariant=Color(0xffe5e5e5))

@Composable fun PocketTheme(mode:ThemeMode?=null,content:@Composable ()->Unit) {
    val stored=rememberThemeMode()
    val dark=when(mode ?: stored) { ThemeMode.SYSTEM->isSystemInDarkTheme();ThemeMode.LIGHT->false;ThemeMode.DARK->true }
    val activity=androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity
    SideEffect { activity?.let {
        WindowCompat.getInsetsController(it.window,it.window.decorView).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark }
    } }
    CompositionLocalProvider(LocalPocketDark provides dark) {
        MaterialTheme(colorScheme=if(dark) NightColors else DayColors,
            shapes=Shapes(extraSmall=RoundedCornerShape(4.dp),small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(12.dp),large=RoundedCornerShape(16.dp),extraLarge=RoundedCornerShape(20.dp)),
            typography=Typography(
                headlineLarge=TextStyle(fontFamily=androidx.compose.ui.text.font.FontFamily.Serif,fontWeight=FontWeight.Medium,fontSize=32.sp,lineHeight=40.sp,letterSpacing=(-0.4).sp),
                headlineMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=26.sp,lineHeight=34.sp),
                headlineSmall=TextStyle(fontWeight=FontWeight.Medium,fontSize=20.sp,lineHeight=28.sp),
                titleLarge=TextStyle(fontWeight=FontWeight.Medium,fontSize=20.sp,lineHeight=28.sp),
                titleMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=24.sp),
                titleSmall=TextStyle(fontWeight=FontWeight.Medium,fontSize=14.sp,lineHeight=20.sp),
                bodyLarge=TextStyle(fontSize=16.sp,lineHeight=24.sp),
                bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
                bodySmall=TextStyle(fontSize=12.sp,lineHeight=18.sp),
                labelLarge=TextStyle(fontWeight=FontWeight.Medium,fontSize=14.sp,lineHeight=20.sp),
                labelMedium=TextStyle(fontWeight=FontWeight.Medium,fontSize=12.sp,lineHeight=16.sp),
                labelSmall=TextStyle(fontSize=11.sp,lineHeight=16.sp)),content=content)
    }
}
@Composable fun AppearanceDialog(close:()->Unit) {
    val context=LocalContext.current
    val mode=rememberThemeMode()
    GlassAlertDialog(onDismissRequest=close,title={Text("外观")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("选择适合你的观看环境",color=MaterialTheme.colorScheme.onSurfaceVariant)
        ThemeMode.entries.forEach { choice ->
            Surface(shape=MaterialTheme.shapes.medium,color=glassFill(mode==choice),border=null) {
                Row(Modifier.fillMaxWidth().selectable(mode==choice,role=Role.RadioButton,onClick={ThemePreference.write(context,choice)}).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected=mode==choice,onClick=null)
                    Column { Text(choice.title,style=MaterialTheme.typography.titleSmall);Text(choice.description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }},confirmButton={GlassTextButton(onClick=close) { Text("完成") }})
}
