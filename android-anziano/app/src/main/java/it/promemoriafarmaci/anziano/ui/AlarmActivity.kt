package it.promemoriafarmaci.anziano.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.promemoriafarmaci.anziano.alarm.AlarmSound
import it.promemoriafarmaci.anziano.alarm.Reminder
import it.promemoriafarmaci.anziano.data.ActiveDose
import it.promemoriafarmaci.anziano.data.Store
import it.promemoriafarmaci.anziano.data.Tutor
import it.promemoriafarmaci.anziano.lock.LockManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Schermata del farmaco. Con l'app proprietaria del dispositivo il telefono resta bloccato
 * su questa schermata finché il farmaco non viene confermato (restano chiamate e 112).
 */
class AlarmActivity : ComponentActivity() {
    private lateinit var sound: AlarmSound
    private val doses = mutableStateOf<List<ActiveDose>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        sound = AlarmSound(this)
        onBackPressedDispatcher.addCallback(this) { /* Indietro disattivato finché non si conferma. */ }
        val store = Store.get(this)
        setContent {
            AppTheme {
                AlarmScreen(
                    doses = doses.value,
                    tutors = store.schedule.tutors,
                    zone = store.schedule.zone(),
                    onTaken = {
                        Reminder.confirmDoses(this)
                        done()
                    },
                    onCall = { phone -> sound.stop(); Calls.call(this, phone) },
                    onEmergencyCall = { sound.stop(); Calls.emergency(this) },
                    onEmergencyUnlock = {
                        Reminder.emergencyUnlock(this)
                        done()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        doses.value = Store.get(this).activeAlarm.doses
        if (doses.value.isEmpty()) {
            done()
            return
        }
        LockManager.lock(this)
        sound.start()
    }

    override fun onPause() {
        super.onPause()
        sound.stop()
    }

    private fun done() {
        sound.stop()
        LockManager.unlock(this)
        finish()
    }
}

@Composable
private fun AlarmScreen(
    doses: List<ActiveDose>,
    tutors: List<Tutor>,
    zone: ZoneId,
    onTaken: () -> Unit,
    onCall: (String) -> Unit,
    onEmergencyCall: () -> Unit,
    onEmergencyUnlock: () -> Unit,
) {
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    Column(
        Modifier.fillMaxSize().background(Color(0xFFFFF8E1)).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("È ORA DEL FARMACO", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Palette.Blue,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        doses.forEach { dose ->
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp),
            ) {
                Text(dose.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                if (dose.dosage.isNotBlank()) Text(dose.dosage, fontSize = 26.sp, color = Color.Black)
                if (dose.instructions.isNotBlank()) Text(dose.instructions, fontSize = 22.sp, color = Palette.Grey)
                Text("Ore " + Instant.ofEpochMilli(dose.scheduledForMillis).atZone(zone).format(fmt),
                    fontSize = 20.sp, color = Palette.Grey)
            }
        }
        Spacer(Modifier.height(8.dp))
        BigButton("✓ HO PRESO IL FARMACO", Palette.Green, onTaken, minHeight = 140, fontSize = 32)
        Spacer(Modifier.height(16.dp))
        tutors.filter { !it.phone.isNullOrBlank() }.forEach { tutor ->
            BigButton("📞 Chiama ${tutor.name}", Palette.Blue, { onCall(tutor.phone!!) }, minHeight = 72, fontSize = 24)
        }
        BigButton("🚑 Emergenza 112", Palette.Red, onEmergencyCall, minHeight = 72, fontSize = 24)
        Spacer(Modifier.height(24.dp))
        HoldButton("Sblocco di emergenza (tieni premuto 3 secondi).\nIl tuo tutore verrà avvisato.", 3000, onEmergencyUnlock)
    }
}
