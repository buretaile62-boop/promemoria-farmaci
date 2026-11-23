# Promemoria Farmaci

App per persone dai 70 ai 99 anni che ricorda di prendere i farmaci, con un secondo percorso per il **tutore** (familiare o badante), che riceve un avviso quando il suo caro salta una dose o non risponde al controllo "STO BENE".

| Componente | Cartella | Tecnologia | Dove gira |
|---|---|---|---|
| App dell'anziano | `android-anziano/` | Android nativo (Kotlin, Jetpack Compose) | Telefono Android dell'anziano |
| App del tutore | `app-tutore/` | Expo / React Native | Android, iPhone e web (Vercel) |
| Backend | `supabase/` | Supabase (PostgreSQL, Auth, Edge Function) | Cloud Supabase |

## Come funziona

**Anziano**
- All'ora del farmaco il telefono suona come una sveglia e mostra una schermata a tutto schermo con nome, dose e indicazioni, e un grande pulsante verde **HO PRESO IL FARMACO**.
- **Telefono bloccato finché non conferma**: con la configurazione "proprietario del dispositivo" (sotto), il telefono resta fermo sulla schermata del farmaco. Restano sempre disponibili:
  - le chiamate ai tutori e quelle in arrivo;
  - il **112**;
  - il menu di spegnimento/emergenza del telefono;
  - uno **sblocco di emergenza** (pulsante da tenere premuto 3 secondi), che libera il telefono per 30 minuti e avvisa il tutore.
- Senza quella configurazione non c'è blocco: l'allarme si ripresenta ogni 3 minuti finché non viene confermato, fino a 2 ore.
- **STO BENE**: alle 8, 12, 16 e 20 compare, anche ad app chiusa e a schermo bloccato, una schermata rossa a tutto schermo. Basta toccarla. Se il controllo coincide con un farmaco, confermare il farmaco vale anche come "sto bene".
- Funziona senza rete: allarmi e conferme sono locali e le conferme vengono inviate appena torna Internet.
- Niente email né password: l'account dell'anziano è legato al telefono, che mostra un codice di 6 cifre da dare al tutore.

**Tutore**
- Si registra con email e password e collega l'anziano con il codice di 6 cifre.
- Inserisce la terapia (farmaco, dose, indicazioni e orari). Il telefono dell'anziano la riceve entro 15 minuti, o subito quando l'anziano apre l'app.
- Riceve una notifica push quando:
  - una dose non viene confermata entro **30 minuti**;
  - "STO BENE" resta senza risposta per **15 minuti**;
  - viene usato lo sblocco di emergenza;
  - una dose saltata viene poi confermata in ritardo (avviso di recupero).
- Vede lo storico di 7 giorni, l'ultimo "STO BENE" e quando il telefono dell'anziano è stato attivo l'ultima volta.

Le regole temporali stanno nel database (`supabase/migrations/…_init.sql`: `dose_grace`, `wellness_grace`, `wellness_slot_hours`). Gli orari di "STO BENE" vanno tenuti allineati con `DEFAULT_WELLNESS_HOURS` in `Models.kt`, che li usa solo prima della prima sincronizzazione.

## 1. Supabase

1. Crea un progetto su supabase.com.
2. Abilita **Authentication → Sign In / Providers → Anonymous sign-ins**: serve all'app dell'anziano.
3. Applica lo schema con `supabase link --project-ref <ref>` e poi `supabase db push`, oppure incolla `supabase/migrations/20261123000000_init.sql` nello SQL editor.
4. Pubblica la funzione che invia gli avvisi:
   ```bash
   supabase functions deploy notify-tutors --no-verify-jwt
   ```
   ```bash
   supabase secrets set CRON_SECRET=$(openssl rand -hex 32)
   ```
5. Abilita le estensioni `pg_cron` e `pg_net`, poi esegui `supabase/cron.sql` con il tuo project ref e lo stesso `CRON_SECRET`. Il controllo parte ogni minuto.

Le chiavi da usare nelle app sono **Project URL** e **anon key**. La `service_role` key resta solo dentro Supabase.

## 2. App dell'anziano (Android)

Requisiti: JDK 17 e Android SDK 35.

Crea `android-anziano/local.properties` (non va versionato):

```properties
sdk.dir=/percorso/Android/sdk
SUPABASE_URL=https://xxxx.supabase.co
SUPABASE_ANON_KEY=eyJ...
```

Poi compila:

```bash
cd android-anziano && ./gradlew assembleDebug
```

L'APK si trova in `app/build/outputs/apk/debug/app-debug.apk`. Al primo avvio l'app mostra in rosso i permessi mancanti: notifiche, sveglie puntuali, avvisi a tutto schermo, "mostra sopra le altre app" ed esclusione dal risparmio batteria. Concedili tutti: senza questi permessi Android può ritardare o nascondere gli allarmi.

### Attivare il blocco del telefono (consigliato, una volta sola)

Il blocco completo richiede che l'app sia **proprietaria del dispositivo**. Android lo consente solo su un telefono **senza account configurati**, cioè appena ripristinato o prima di aggiungere l'account Google:

1. Ripristina il telefono e salta l'aggiunta dell'account Google.
2. Attiva le Opzioni sviluppatore e il Debug USB.
3. Installa l'APK ed esegui da un computer:
   ```bash
   adb shell dpm set-device-owner it.promemoriafarmaci.anziano/.lock.AdminReceiver
   ```
4. Dopo puoi aggiungere l'account Google e le altre app.

Per rimuoverlo: Impostazioni → Sicurezza → App di amministrazione, oppure ripristina il telefono.

> ⚠️ **Consenso.** Il blocco limita il telefono di una persona. Va attivato solo con il consenso informato dell'anziano, o di chi ne ha la rappresentanza legale, e mai come misura punitiva. Chiamate, 112 e sblocco di emergenza restano sempre disponibili per scelta progettuale: non vanno rimossi.

## 3. App del tutore

```bash
cd app-tutore && cp .env.example .env
```

Compila `.env` con URL e anon key, poi avvia:

```bash
npm install && npx expo start
```

- **Web / Vercel**: `npm run build:web` produce `dist/`. Su Vercel il progetto ha **Root Directory** `app-tutore` (vedi `vercel.json`) e le variabili `EXPO_PUBLIC_SUPABASE_URL` / `EXPO_PUBLIC_SUPABASE_ANON_KEY`. Sul web gli avvisi si vedono solo a pagina aperta.
- **Notifiche push sul telefono**: servono un build nativo (`eas build`) e `extra.eas.projectId` in `app.json`, che si ottiene con `eas init`. Su Expo Go le push non sono garantite.

## Test

```bash
npm install && npm run test:db
```

Testa lo schema e i permessi su PGlite, cioè Postgres in WebAssembly, senza Supabase.

```bash
cd app-tutore && npm test && npm run typecheck
```

```bash
cd android-anziano && ./gradlew testDebugUnitTest
```

## Limiti noti

- **iPhone per l'anziano**: iOS non consente allarmi a tutto schermo ad app chiusa né il blocco del telefono, per questo l'app dell'anziano è solo Android. L'app del tutore funziona anche su iPhone.
- **Produttori aggressivi** (alcuni Xiaomi, Huawei, Oppo): oltre al risparmio batteria può servire attivare l'"avvio automatico" dalle impostazioni del produttore.
- **Telefono spento o scarico**: la conferma non può arrivare, quindi il tutore riceve "dose saltata", e l'orario di ultima attività del telefono aiuta a capire il motivo.
- Non sostituisce un dispositivo medico o di telesoccorso certificato.
