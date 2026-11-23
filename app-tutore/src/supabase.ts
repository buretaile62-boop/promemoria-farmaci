import 'react-native-url-polyfill/auto';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { createClient } from '@supabase/supabase-js';
import { Platform } from 'react-native';

const url = process.env.EXPO_PUBLIC_SUPABASE_URL ?? '';
const anonKey = process.env.EXPO_PUBLIC_SUPABASE_ANON_KEY ?? '';

export const isConfigured = url.startsWith('https://') && anonKey.length > 20;

export const supabase = createClient(isConfigured ? url : 'https://non-configurato.invalid', anonKey || 'x', {
  auth: {
    storage: Platform.OS === 'web' ? undefined : AsyncStorage,
    autoRefreshToken: true,
    persistSession: true,
    detectSessionInUrl: Platform.OS === 'web',
  },
});

export type Profile = {
  id: string;
  role: 'anziano' | 'tutore';
  display_name: string;
  phone: string | null;
  timezone: string;
  last_seen_at: string | null;
};

export type Medication = {
  id: string;
  name: string;
  dosage: string;
  instructions: string;
  active: boolean;
  medication_times: { id: string; time_of_day: string }[];
};

export type DoseEvent = {
  id: string;
  scheduled_for: string;
  status: 'taken' | 'missed';
  confirmed_at: string | null;
  medication_times: { time_of_day: string; medications: { name: string } | null } | null;
};

export type WellnessCheck = {
  id: string;
  slot_at: string;
  status: 'ok' | 'missed';
  answered_at: string | null;
};

export type Alert = {
  id: string;
  elder_id: string;
  kind: 'dose_missed' | 'dose_taken_late' | 'wellness_missed' | 'emergency_unlock';
  title: string;
  body: string;
  created_at: string;
};

/** Traduce gli errori del database in messaggi comprensibili. */
export function friendlyError(error: unknown): string {
  const message = error instanceof Error ? error.message : String((error as { message?: string })?.message ?? error);
  if (message.includes('invalid_code')) return 'Codice non valido o scaduto. Chiedi un nuovo codice sul telefono del tuo caro.';
  if (message.includes('only_tutor')) return 'Questo account non è un account tutore.';
  if (message.includes('Invalid login credentials')) return 'Email o password errate.';
  if (message.includes('row-level security')) return 'Operazione non consentita.';
  return message;
}
