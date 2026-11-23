import { StatusBar } from 'expo-status-bar';
import type { Session } from '@supabase/supabase-js';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Alert as NativeAlert,
  Linking,
  Platform,
  Pressable,
  RefreshControl,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { registerForPush } from './src/push';
import {
  friendlyError,
  isConfigured,
  supabase,
  type Alert,
  type DoseEvent,
  type Medication,
  type Profile,
  type WellnessCheck,
} from './src/supabase';
import { ago, formatDateTime, hhmm, parseTimes } from './src/time';

const C = {
  bg: '#F4F6F8',
  card: '#FFFFFF',
  text: '#14202B',
  muted: '#5A6B7A',
  primary: '#1F6FEB',
  danger: '#C62828',
  ok: '#2E7D32',
  warn: '#B26A00',
  border: '#D9E0E6',
};

function confirmAction(message: string, onYes: () => void) {
  if (Platform.OS === 'web') {
    if (globalThis.confirm?.(message)) onYes();
    return;
  }
  NativeAlert.alert('Conferma', message, [
    { text: 'Annulla', style: 'cancel' },
    { text: 'Sì', style: 'destructive', onPress: onYes },
  ]);
}

// ---------------------------------------------------------------------------
// Componenti di base
// ---------------------------------------------------------------------------

function Button(props: { title: string; onPress: () => void; kind?: 'primary' | 'danger' | 'ghost'; disabled?: boolean }) {
  const kind = props.kind ?? 'primary';
  return (
    <Pressable
      accessibilityRole="button"
      disabled={props.disabled}
      onPress={props.onPress}
      style={({ pressed }) => [
        styles.button,
        kind === 'danger' && { backgroundColor: C.danger },
        kind === 'ghost' && { backgroundColor: 'transparent', borderWidth: 1, borderColor: C.border },
        (pressed || props.disabled) && { opacity: 0.6 },
      ]}
    >
      <Text style={[styles.buttonText, kind === 'ghost' && { color: C.primary }]}>{props.title}</Text>
    </Pressable>
  );
}

function Field(props: {
  label: string;
  value: string;
  onChangeText: (v: string) => void;
  secure?: boolean;
  placeholder?: string;
  keyboard?: 'default' | 'email-address' | 'number-pad' | 'phone-pad';
}) {
  return (
    <View style={{ marginBottom: 12 }}>
      <Text style={styles.label}>{props.label}</Text>
      <TextInput
        style={styles.input}
        value={props.value}
        onChangeText={props.onChangeText}
        secureTextEntry={props.secure}
        placeholder={props.placeholder}
        placeholderTextColor="#94A3B0"
        autoCapitalize="none"
        keyboardType={props.keyboard ?? 'default'}
      />
    </View>
  );
}

function Card({ children, tone }: { children: ReactNode; tone?: 'danger' | 'ok' | 'warn' }) {
  const border = tone === 'danger' ? C.danger : tone === 'ok' ? C.ok : tone === 'warn' ? C.warn : C.border;
  return <View style={[styles.card, { borderLeftColor: border }]}>{children}</View>;
}

function ErrorText({ text }: { text: string | null }) {
  return text ? <Text style={styles.error}>{text}</Text> : null;
}

// ---------------------------------------------------------------------------
// Accesso e registrazione
// ---------------------------------------------------------------------------

function AuthScreen() {
  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    setInfo(null);
    const credentials = { email: email.trim(), password };
    const { data, error } =
      mode === 'login'
        ? await supabase.auth.signInWithPassword(credentials)
        : await supabase.auth.signUp(credentials);
    setBusy(false);
    if (error) setError(friendlyError(error));
    else if (mode === 'signup' && !data.session) setInfo('Ti abbiamo inviato un’email: conferma l’indirizzo e poi accedi.');
  }

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Text style={styles.h1}>Promemoria Farmaci</Text>
      <Text style={styles.subtitle}>Area tutore: segui la terapia del tuo caro e ricevi un avviso se salta un farmaco.</Text>
      <Card>
        <Field label="Email" value={email} onChangeText={setEmail} keyboard="email-address" />
        <Field label="Password" value={password} onChangeText={setPassword} secure />
        <ErrorText text={error} />
        {info && <Text style={{ color: C.ok, marginBottom: 8 }}>{info}</Text>}
        <Button title={mode === 'login' ? 'Accedi' : 'Crea account'} onPress={submit} disabled={busy || !email || password.length < 8} />
        <Button
          kind="ghost"
          title={mode === 'login' ? 'Non hai un account? Registrati' : 'Hai già un account? Accedi'}
          onPress={() => setMode(mode === 'login' ? 'signup' : 'login')}
        />
        {mode === 'signup' && <Text style={styles.muted}>Password di almeno 8 caratteri.</Text>}
      </Card>
    </ScrollView>
  );
}

function RegisterTutor({ onDone }: { onDone: () => void }) {
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function save() {
    const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Europe/Rome';
    const { error } = await supabase.rpc('register_profile', {
      p_role: 'tutore',
      p_display_name: name.trim(),
      p_timezone: timezone,
      p_phone: phone.trim() || null,
    });
    if (error) setError(friendlyError(error));
    else onDone();
  }

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Text style={styles.h1}>Il tuo profilo</Text>
      <Card>
        <Field label="Il tuo nome (lo vedrà il tuo caro)" value={name} onChangeText={setName} />
        <Field
          label="Il tuo telefono (per il pulsante “Chiama” sul telefono del tuo caro)"
          value={phone}
          onChangeText={setPhone}
          keyboard="phone-pad"
          placeholder="+39 333 1234567"
        />
        <ErrorText text={error} />
        <Button title="Salva" onPress={save} disabled={!name.trim()} />
      </Card>
    </ScrollView>
  );
}

// ---------------------------------------------------------------------------
// Elenco assistiti
// ---------------------------------------------------------------------------

type ElderSummary = Profile & { lastWellness: WellnessCheck | null; missedToday: number };

function HomeScreen(props: { me: Profile; onOpen: (elder: Profile) => void; pushStatus: string | null }) {
  const [elders, setElders] = useState<ElderSummary[] | null>(null);
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    setRefreshing(true);
    const links = await supabase.from('care_links').select('elder_id').eq('tutor_id', props.me.id);
    const ids = (links.data ?? []).map((l) => l.elder_id as string);
    const since = new Date(Date.now() - 24 * 3600e3).toISOString();
    const empty = Promise.resolve({ data: [] as unknown[] });
    const [profiles, wellness, doses, recent] = await Promise.all([
      ids.length ? supabase.from('profiles').select('*').in('id', ids) : empty,
      ids.length
        ? supabase.from('wellness_checks').select('*').in('elder_id', ids).order('slot_at', { ascending: false }).limit(50)
        : empty,
      ids.length
        ? supabase.from('dose_events').select('elder_id').in('elder_id', ids).eq('status', 'missed').gte('scheduled_for', since)
        : empty,
      supabase.from('alerts').select('*').order('created_at', { ascending: false }).limit(20),
    ]);
    const wellnessRows = (wellness.data ?? []) as (WellnessCheck & { elder_id: string })[];
    const doseRows = (doses.data ?? []) as { elder_id: string }[];
    const list = ((profiles.data ?? []) as Profile[]).map((p) => ({
      ...p,
      lastWellness: wellnessRows.find((w) => w.elder_id === p.id) ?? null,
      missedToday: doseRows.filter((d) => d.elder_id === p.id).length,
    }));
    setElders(list);
    setAlerts((recent.data ?? []) as Alert[]);
    setRefreshing(false);
  }, [props.me.id]);

  useEffect(() => {
    load();
    const t = setInterval(load, 60_000);
    return () => clearInterval(t);
  }, [load]);

  async function pair() {
    setError(null);
    const { error } = await supabase.rpc('claim_pairing_code', { p_code: code.trim() });
    if (error) setError(friendlyError(error));
    else {
      setCode('');
      load();
    }
  }

  return (
    <ScrollView contentContainerStyle={styles.screen} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={load} />}>
      <View style={styles.rowBetween}>
        <Text style={styles.h1}>Ciao {props.me.display_name}</Text>
        <Pressable onPress={() => supabase.auth.signOut()}>
          <Text style={{ color: C.primary }}>Esci</Text>
        </Pressable>
      </View>
      {props.pushStatus && <Text style={[styles.muted, { marginBottom: 12 }]}>{props.pushStatus}</Text>}

      <Text style={styles.h2}>I tuoi assistiti</Text>
      {elders === null && <ActivityIndicator />}
      {elders?.length === 0 && <Text style={styles.muted}>Nessun assistito collegato. Inserisci il codice qui sotto.</Text>}
      {elders?.map((e) => {
        const wellnessBad = e.lastWellness?.status === 'missed';
        const tone = e.missedToday > 0 || wellnessBad ? 'danger' : 'ok';
        return (
          <Pressable key={e.id} onPress={() => props.onOpen(e)}>
            <Card tone={tone}>
              <Text style={styles.h3}>{e.display_name}</Text>
              <Text style={styles.muted}>Telefono attivo: {ago(e.last_seen_at)}</Text>
              <Text style={{ color: wellnessBad ? C.danger : C.text, marginTop: 4 }}>
                {e.lastWellness
                  ? wellnessBad
                    ? `⚠️ Nessuna risposta a STO BENE (${formatDateTime(e.lastWellness.slot_at, e.timezone)})`
                    : `✅ Sta bene (${formatDateTime(e.lastWellness.answered_at ?? e.lastWellness.slot_at, e.timezone)})`
                  : 'Nessun controllo STO BENE ancora'}
              </Text>
              <Text style={{ color: e.missedToday ? C.danger : C.ok }}>
                {e.missedToday ? `⚠️ ${e.missedToday} dosi saltate nelle ultime 24 ore` : '✅ Nessuna dose saltata nelle ultime 24 ore'}
              </Text>
            </Card>
          </Pressable>
        );
      })}

      <Text style={styles.h2}>Collega un assistito</Text>
      <Card>
        <Text style={[styles.muted, { marginBottom: 8 }]}>
          Sul telefono del tuo caro apri l’app Promemoria Farmaci: comparirà un codice di 6 cifre.
        </Text>
        <Field label="Codice" value={code} onChangeText={(v) => setCode(v.replace(/\D/g, '').slice(0, 6))} keyboard="number-pad" />
        <ErrorText text={error} />
        <Button title="Collega" onPress={pair} disabled={code.length !== 6} />
      </Card>

      <Text style={styles.h2}>Ultimi avvisi</Text>
      {alerts.length === 0 && <Text style={styles.muted}>Nessun avviso.</Text>}
      {alerts.map((a) => (
        <AlertRow key={a.id} alert={a} />
      ))}
    </ScrollView>
  );
}

function AlertRow({ alert }: { alert: Alert }) {
  const tone = alert.kind === 'dose_taken_late' ? 'warn' : 'danger';
  return (
    <Card tone={tone}>
      <Text style={styles.h3}>{alert.title}</Text>
      <Text>{alert.body}</Text>
      <Text style={styles.muted}>{formatDateTime(alert.created_at)}</Text>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Dettaglio assistito
// ---------------------------------------------------------------------------

function ElderScreen({ elder, onBack }: { elder: Profile; onBack: () => void }) {
  const [tab, setTab] = useState<'stato' | 'terapia'>('stato');
  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Pressable onPress={onBack}>
        <Text style={{ color: C.primary, marginBottom: 8 }}>‹ Indietro</Text>
      </Pressable>
      <Text style={styles.h1}>{elder.display_name}</Text>
      <View style={styles.tabs}>
        {(['stato', 'terapia'] as const).map((t) => (
          <Pressable key={t} onPress={() => setTab(t)} style={[styles.tab, tab === t && styles.tabActive]}>
            <Text style={[styles.tabText, tab === t && { color: '#fff' }]}>{t === 'stato' ? 'Stato' : 'Terapia'}</Text>
          </Pressable>
        ))}
      </View>
      {tab === 'stato' ? <StatusTab elder={elder} /> : <TherapyTab elder={elder} />}
    </ScrollView>
  );
}

function StatusTab({ elder }: { elder: Profile }) {
  const [doses, setDoses] = useState<DoseEvent[] | null>(null);
  const [wellness, setWellness] = useState<WellnessCheck[]>([]);

  useEffect(() => {
    const since = new Date(Date.now() - 7 * 24 * 3600e3).toISOString();
    supabase
      .from('dose_events')
      .select('id, scheduled_for, status, confirmed_at, medication_times(time_of_day, medications(name))')
      .eq('elder_id', elder.id)
      .gte('scheduled_for', since)
      .order('scheduled_for', { ascending: false })
      .then(({ data }) => setDoses((data ?? []) as unknown as DoseEvent[]));
    supabase
      .from('wellness_checks')
      .select('*')
      .eq('elder_id', elder.id)
      .gte('slot_at', since)
      .order('slot_at', { ascending: false })
      .limit(12)
      .then(({ data }) => setWellness((data ?? []) as WellnessCheck[]));
  }, [elder.id]);

  return (
    <View>
      <Text style={styles.muted}>Ultima attività del telefono: {ago(elder.last_seen_at)}</Text>

      <Text style={styles.h2}>STO BENE</Text>
      {wellness.length === 0 && <Text style={styles.muted}>Nessun controllo registrato.</Text>}
      {wellness.map((w) => (
        <Text key={w.id} style={{ color: w.status === 'ok' ? C.ok : C.danger, marginBottom: 4 }}>
          {w.status === 'ok' ? '✅' : '⚠️'} Controllo {formatDateTime(w.slot_at, elder.timezone)} —{' '}
          {w.status === 'ok' && w.answered_at ? `risposto ${formatDateTime(w.answered_at, elder.timezone)}` : 'nessuna risposta'}
        </Text>
      ))}

      <Text style={styles.h2}>Farmaci (ultimi 7 giorni)</Text>
      {doses === null && <ActivityIndicator />}
      {doses?.length === 0 && <Text style={styles.muted}>Nessuna dose registrata.</Text>}
      {doses?.map((d) => (
        <Text key={d.id} style={{ color: d.status === 'taken' ? C.ok : C.danger, marginBottom: 4 }}>
          {d.status === 'taken' ? '✅' : '⚠️'} {formatDateTime(d.scheduled_for, elder.timezone)} —{' '}
          {d.medication_times?.medications?.name ?? 'Farmaco'} {d.status === 'taken' ? 'preso' : 'non confermato'}
        </Text>
      ))}
    </View>
  );
}

function TherapyTab({ elder }: { elder: Profile }) {
  const [meds, setMeds] = useState<Medication[] | null>(null);
  const [name, setName] = useState('');
  const [dosage, setDosage] = useState('');
  const [instructions, setInstructions] = useState('');
  const [times, setTimes] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const { data, error } = await supabase
      .from('medications')
      .select('id, name, dosage, instructions, active, medication_times(id, time_of_day)')
      .eq('elder_id', elder.id)
      .eq('active', true)
      .order('name');
    if (error) setError(friendlyError(error));
    setMeds((data ?? []) as Medication[]);
  }, [elder.id]);

  useEffect(() => {
    load();
  }, [load]);

  async function add() {
    setError(null);
    const parsed = parseTimes(times);
    if (!parsed) {
      setError('Orari non validi. Esempio: 8:00, 13:30, 20:00');
      return;
    }
    setBusy(true);
    const { data, error } = await supabase
      .from('medications')
      .insert({ elder_id: elder.id, name: name.trim(), dosage: dosage.trim(), instructions: instructions.trim() })
      .select('id')
      .single();
    if (error || !data) {
      setError(friendlyError(error));
      setBusy(false);
      return;
    }
    const res = await supabase.from('medication_times').insert(parsed.map((t) => ({ medication_id: data.id, time_of_day: t })));
    if (res.error) {
      // Nessun farmaco senza orari: annulla l'inserimento.
      await supabase.from('medications').delete().eq('id', data.id);
      setError(friendlyError(res.error));
    } else {
      setName('');
      setDosage('');
      setInstructions('');
      setTimes('');
    }
    setBusy(false);
    load();
  }

  function remove(med: Medication) {
    confirmAction(`Togliere ${med.name} dalla terapia? Lo storico resta disponibile.`, async () => {
      const { error } = await supabase.from('medications').update({ active: false }).eq('id', med.id);
      if (error) setError(friendlyError(error));
      load();
    });
  }

  return (
    <View>
      <Text style={[styles.muted, { marginBottom: 8 }]}>
        Le modifiche arrivano sul telefono del tuo caro entro 15 minuti, o subito quando apre l’app.
      </Text>
      {meds === null && <ActivityIndicator />}
      {meds?.length === 0 && <Text style={styles.muted}>Nessun farmaco in terapia.</Text>}
      {meds?.map((m) => (
        <Card key={m.id}>
          <Text style={styles.h3}>
            {m.name} {m.dosage}
          </Text>
          {m.instructions ? <Text>{m.instructions}</Text> : null}
          <Text style={{ marginVertical: 4 }}>
            Orari: {m.medication_times.map((t) => hhmm(t.time_of_day)).sort().join(' · ')}
          </Text>
          <Button kind="ghost" title="Togli dalla terapia" onPress={() => remove(m)} />
        </Card>
      ))}

      <Text style={styles.h2}>Aggiungi farmaco</Text>
      <Card>
        <Field label="Nome" value={name} onChangeText={setName} placeholder="Cardioaspirina" />
        <Field label="Dose" value={dosage} onChangeText={setDosage} placeholder="1 compressa da 100 mg" />
        <Field label="Indicazioni" value={instructions} onChangeText={setInstructions} placeholder="Dopo colazione" />
        <Field label="Orari (separati da virgola)" value={times} onChangeText={setTimes} placeholder="8:00, 20:00" />
        <ErrorText text={error} />
        <Button title="Aggiungi" onPress={add} disabled={busy || !name.trim() || !times.trim()} />
      </Card>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Radice
// ---------------------------------------------------------------------------

export default function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  const [me, setMe] = useState<Profile | null | undefined>(undefined);
  const [openElder, setOpenElder] = useState<Profile | null>(null);
  const [pushStatus, setPushStatus] = useState<string | null>(null);

  useEffect(() => {
    if (!isConfigured) return;
    supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data } = supabase.auth.onAuthStateChange((_event, s) => setSession(s));
    return () => data.subscription.unsubscribe();
  }, []);

  const loadMe = useCallback(async () => {
    if (!session) {
      setMe(undefined);
      return;
    }
    const { data } = await supabase.from('profiles').select('*').eq('id', session.user.id).maybeSingle();
    setMe((data as Profile | null) ?? null);
  }, [session]);

  useEffect(() => {
    loadMe();
  }, [loadMe]);

  useEffect(() => {
    if (me?.role === 'tutore') registerForPush(me.id).then(setPushStatus).catch((e) => setPushStatus(String(e)));
  }, [me]);

  let content: ReactNode;
  if (!isConfigured) {
    content = (
      <View style={styles.screen}>
        <Text style={styles.h1}>Configurazione mancante</Text>
        <Text>Imposta EXPO_PUBLIC_SUPABASE_URL ed EXPO_PUBLIC_SUPABASE_ANON_KEY (vedi README).</Text>
      </View>
    );
  } else if (session === undefined || (session && me === undefined)) {
    content = <ActivityIndicator style={{ marginTop: 80 }} size="large" />;
  } else if (!session) {
    content = <AuthScreen />;
  } else if (me === null) {
    content = <RegisterTutor onDone={loadMe} />;
  } else if (me && me.role !== 'tutore') {
    content = (
      <View style={styles.screen}>
        <Text style={styles.h1}>Account non valido</Text>
        <Text>Questo account appartiene all’app dell’anziano.</Text>
        <Button title="Esci" onPress={() => supabase.auth.signOut()} />
      </View>
    );
  } else if (openElder) {
    content = <ElderScreen elder={openElder} onBack={() => setOpenElder(null)} />;
  } else if (me) {
    content = <HomeScreen me={me} onOpen={setOpenElder} pushStatus={pushStatus} />;
  }

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: C.bg }}>
      <StatusBar style="dark" />
      <View style={{ flex: 1, width: '100%', maxWidth: 720, alignSelf: 'center' }}>{content}</View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { padding: 16, paddingBottom: 48 },
  h1: { fontSize: 28, fontWeight: '700', color: C.text, marginBottom: 8 },
  h2: { fontSize: 20, fontWeight: '700', color: C.text, marginTop: 24, marginBottom: 8 },
  h3: { fontSize: 18, fontWeight: '600', color: C.text, marginBottom: 2 },
  subtitle: { fontSize: 16, color: C.muted, marginBottom: 16 },
  muted: { color: C.muted, fontSize: 14 },
  label: { fontSize: 15, color: C.text, marginBottom: 4, fontWeight: '600' },
  input: {
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 12,
    fontSize: 17,
    backgroundColor: '#fff',
    color: C.text,
  },
  button: {
    backgroundColor: C.primary,
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
    marginVertical: 6,
  },
  buttonText: { color: '#fff', fontSize: 17, fontWeight: '700' },
  card: {
    backgroundColor: C.card,
    borderRadius: 12,
    padding: 14,
    marginBottom: 10,
    borderLeftWidth: 5,
    borderWidth: 1,
    borderColor: C.border,
  },
  error: { color: C.danger, marginBottom: 8 },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  tabs: { flexDirection: 'row', gap: 8, marginVertical: 12 },
  tab: { paddingVertical: 8, paddingHorizontal: 18, borderRadius: 20, borderWidth: 1, borderColor: C.primary },
  tabActive: { backgroundColor: C.primary },
  tabText: { color: C.primary, fontWeight: '600', fontSize: 16 },
});
