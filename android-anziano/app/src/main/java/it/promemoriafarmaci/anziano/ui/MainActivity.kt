package it.promemoriafarmaci.anziano.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import it.promemoriafarmaci.anziano.alarm.AlarmScheduler
import it.promemoriafarmaci.anziano.alarm.Planner
import it.promemoriafarmaci.anziano.alarm.Reminder
import it.promemoriafarmaci.anziano.data.SupabaseApi
import it.promemoriafarmaci.anziano.data.Store
import it.promemoriafarmaci.anziano.data.SyncWorker
import it.promemoriafarmaci.anziano.lock.LockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    /** Incrementato a ogni ritorno sulla schermata per rileggere stato e permessi. */
    private val refresh = mutableIntStateOf(0)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val store = Store.get(this)
                if (refresh.intValue >= 0 && !store.registered) {
                    Welcome(onRegistered = { refresh.intValue++ })
                } else {
                    // key(): rilegge stato e permessi a ogni ritorno sulla schermata.
                    androidx.compose.runtime.key(refresh.intValue) { Home(onChanged = { refresh.intValue++ }) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
        val store = Store.get(this)
        if (store.registered) {
            SyncWorker.syncNow(this)
            AlarmScheduler.scheduleNext(this)
            // Se c'è un promemoria non confermato, riproponilo subito.
            if (!store.activeAlarm.isEmpty && store.nagSuspendedUntil < System.currentTimeMillis()) {
                startActivity(Reminder.screenIntent(this, store.activeAlarm))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Registrazione
    // -----------------------------------------------------------------------

    @Composable
    private fun Welcome(onRegistered: () -> Unit) {
        var name by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Benvenuto", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.Blue)
            Text("Questa app ti ricorda quando prendere i farmaci e avvisa un tuo familiare se ne salti uno.",
                fontSize = 24.sp)
            Text("Come ti chiami?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                textStyle = TextStyle(fontSize = 28.sp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = Palette.Red, fontSize = 20.sp) }
            BigButton(if (busy) "Attendere…" else "INIZIA", Palette.Blue, {
                if (busy || name.isBlank()) return@BigButton
                busy = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { register(name.trim()) } }
                    busy = false
                    result.onSuccess { onRegistered() }.onFailure {
                        error = "Impossibile collegarsi a Internet. Controlla la connessione e riprova."
                    }
                }
            })
        }
    }

    private fun register(name: String) {
        val store = Store.get(this)
        val api = SupabaseApi(store)
        check(api.isConfigured) { "Supabase non configurato" }
        api.rpc("register_profile", buildJsonObject {
            put("p_role", "anziano")
            put("p_display_name", name)
            put("p_timezone", ZoneId.systemDefault().id)
        })
        store.displayName = name
        store.registered = true
        newPairingCode()
        SyncWorker.schedulePeriodic(this)
        SyncWorker.syncNow(this)
    }

    private fun newPairingCode() {
        val store = Store.get(this)
        val code = SupabaseApi(store).rpc("create_pairing_code")?.jsonPrimitive?.content ?: return
        store.pairingCode = code
        store.pairingCodeExpiresAt = System.currentTimeMillis() + 29 * 60_000
    }

    // -----------------------------------------------------------------------
    // Schermata principale
    // -----------------------------------------------------------------------

    @Composable
    private fun Home(onChanged: () -> Unit) {
        val store = Store.get(this)
        val schedule = store.schedule
        val zone = schedule.zone()
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val now = Instant.now()
        val next = Planner.occurrences(schedule, now).firstOrNull { it.at.isAfter(now) && it.doses.isNotEmpty() }
        val missing = missingSetup()

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ciao ${store.displayName ?: ""}", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Palette.Blue)
            Text(LocalTime.now(zone).format(fmt), fontSize = 64.sp, fontWeight = FontWeight.Black)

            if (!store.activeAlarm.isEmpty) {
                BigButton("Hai un promemoria da confermare", Palette.Red, {
                    startActivity(Reminder.screenIntent(this@MainActivity, store.activeAlarm))
                })
            }

            InfoCard {
                if (next != null) {
                    Text("Prossimo farmaco", fontSize = 22.sp, color = Palette.Grey)
                    Text("Ore " + next.at.atZone(zone).format(fmt), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    next.doses.forEach { Text("${it.name} ${it.dosage}".trim(), fontSize = 26.sp) }
                } else {
                    Text("Nessun farmaco in programma.", fontSize = 24.sp)
                    Text("La terapia viene inserita dal tuo tutore.", fontSize = 20.sp, color = Palette.Grey)
                }
            }

            schedule.tutors.filter { !it.phone.isNullOrBlank() }.forEach { tutor ->
                BigButton("📞 Chiama ${tutor.name}", Palette.Blue, { Calls.call(this@MainActivity, tutor.phone!!) })
            }

            if (schedule.tutors.isEmpty()) {
                PairingCard(onChanged)
            }

            if (missing.isNotEmpty()) {
                Text("Da sistemare (chiedi aiuto a un familiare)", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = Palette.Red)
                missing.forEach { (label, action) -> BigButton(label, Palette.Grey, action, minHeight = 64, fontSize = 20) }
            }

            if (schedule.items.isNotEmpty()) {
                Text("Farmaci di ogni giorno", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                schedule.items.sortedBy { it.time }.forEach {
                    Text("${it.time}  ${it.name} ${it.dosage}".trim(), fontSize = 22.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            val lastSync = store.lastSyncMillis
            Text(
                if (lastSync == 0L) "Non ancora sincronizzato"
                else "Aggiornato alle " + Instant.ofEpochMilli(lastSync).atZone(zone).format(fmt) +
                    if (LockManager.isDeviceOwner(this@MainActivity)) " · blocco attivo" else "",
                fontSize = 16.sp, color = Palette.Grey,
            )
            if (schedule.tutors.isNotEmpty()) {
                PairingCard(onChanged, compact = true)
            }
        }
    }

    @Composable
    private fun PairingCard(onChanged: () -> Unit, compact: Boolean = false) {
        val store = Store.get(this)
        val scope = rememberCoroutineScope()
        val code = store.pairingCode
        if (compact && code == null) {
            BigButton("Collega un altro familiare", Palette.Grey, {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { newPairingCode() }
                    withContext(Dispatchers.Main) { onChanged() }
                }
            }, minHeight = 56, fontSize = 18)
            return
        }
        InfoCard {
            Text("Codice per il tuo familiare", fontSize = 22.sp, color = Palette.Grey)
            if (code != null) {
                Text(code.chunked(3).joinToString(" "), fontSize = 56.sp, fontWeight = FontWeight.Black,
                    color = Palette.Blue, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("Il familiare lo inserisce nell’app Tutore. Vale 30 minuti.", fontSize = 18.sp)
            } else {
                BigButton("Crea codice", Palette.Blue, {
                    scope.launch(Dispatchers.IO) {
                        runCatching { newPairingCode() }
                        withContext(Dispatchers.Main) { onChanged() }
                    }
                }, minHeight = 64, fontSize = 22)
            }
        }
    }

    @Composable
    private fun InfoCard(content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFFE3ECFA), RoundedCornerShape(16.dp)).padding(16.dp),
        ) { content() }
    }

    // -----------------------------------------------------------------------
    // Permessi necessari perché i promemoria funzionino ad app chiusa
    // -----------------------------------------------------------------------

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(action: String, withPackage: Boolean = true) {
        val intent = Intent(action)
        if (withPackage) intent.data = Uri.parse("package:$packageName")
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun missingSetup(): List<Pair<String, () -> Unit>> {
        val list = mutableListOf<Pair<String, () -> Unit>>()
        val runtime = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!granted(Manifest.permission.CALL_PHONE)) add(Manifest.permission.CALL_PHONE)
        }
        if (runtime.isNotEmpty()) {
            list += "Consenti notifiche e chiamate" to { askPermissions.launch(runtime.toTypedArray()) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            list += "Consenti sveglie puntuali" to { openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            list += "Consenti avvisi a tutto schermo" to { openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }
        }
        if (!Settings.canDrawOverlays(this) && !LockManager.isDeviceOwner(this)) {
            list += "Consenti “mostra sopra le altre app”" to { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            @Suppress("BatteryLife")
            list += "Escludi dal risparmio batteria" to {
                openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            }
        }
        return list
    }
}
