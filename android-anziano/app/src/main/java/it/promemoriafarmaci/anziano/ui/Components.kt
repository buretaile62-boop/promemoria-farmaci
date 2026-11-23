package it.promemoriafarmaci.anziano.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

object Palette {
    val Blue = Color(0xFF0B3D91)
    val Green = Color(0xFF1B7F3B)
    val Red = Color(0xFFC62828)
    val Grey = Color(0xFF455A64)
    val Background = Color(0xFFFFFFFF)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Palette.Blue, background = Palette.Background),
        content = content,
    )
}

@Composable
fun BigButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Int = 88,
    fontSize: Int = 28,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight.dp).padding(vertical = 6.dp),
    ) {
        Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** Pulsante da tenere premuto [holdMillis] ms: evita sblocchi accidentali. */
@Composable
fun HoldButton(text: String, holdMillis: Long, onConfirmed: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pressed) {
        progress = 0f
        if (!pressed) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (progress < 1f) {
            delay(50)
            progress = ((System.currentTimeMillis() - start).toFloat() / holdMillis).coerceAtMost(1f)
        }
        onConfirmed()
    }
    Box(modifier.fillMaxWidth()) {
        Button(
            onClick = {},
            interactionSource = interaction,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFECEFF1), contentColor = Palette.Grey),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Text(text, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        if (progress > 0f) {
            LinearProgressIndicator(
                progress = { progress },
                color = Palette.Red,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Transparent),
            )
        }
    }
}

object Calls {
    fun call(context: Context, phone: String) {
        val uri = Uri.parse("tel:" + phone.replace(" ", ""))
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** I numeri di emergenza si aprono nel compositore: l'utente preme il tasto verde. */
    fun emergency(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Mostra l'attività sopra il blocco schermo e accende il display. */
fun Activity.showOverLockScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    } else {
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
