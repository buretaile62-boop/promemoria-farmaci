package it.promemoriafarmaci.anziano.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.promemoriafarmaci.anziano.alarm.AlarmSound
import it.promemoriafarmaci.anziano.alarm.Reminder
import it.promemoriafarmaci.anziano.data.Store

/** Pulsante rosso a tutto schermo "STO BENE": tutta la superficie è il pulsante. */
class WellnessActivity : ComponentActivity() {
    private lateinit var sound: AlarmSound

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        sound = AlarmSound(this)
        setContent {
            AppTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.Red)
                        .clickable(role = Role.Button, onClickLabel = "Sto bene") {
                            Reminder.confirmWellness(this)
                            sound.stop()
                            val remaining = Store.get(this).activeAlarm
                            if (remaining.doses.isNotEmpty()) startActivity(Reminder.screenIntent(this, remaining))
                            finish()
                        }
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("STO BENE", fontSize = 72.sp, fontWeight = FontWeight.Black, color = Color.White,
                            textAlign = TextAlign.Center)
                        Text("Tocca lo schermo", fontSize = 28.sp, color = Color.White, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Store.get(this).activeAlarm.wellnessAtMillis == null) {
            finish()
            return
        }
        sound.start()
    }

    override fun onPause() {
        super.onPause()
        sound.stop()
    }
}
