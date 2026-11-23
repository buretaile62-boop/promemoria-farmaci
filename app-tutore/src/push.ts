import Constants from 'expo-constants';
import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';
import { supabase } from './supabase';

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

/**
 * Registra il telefono del tutore per ricevere gli avvisi push.
 * Sul web le notifiche push di Expo non sono disponibili: restituisce un messaggio.
 */
export async function registerForPush(userId: string): Promise<string | null> {
  if (Platform.OS === 'web') {
    return 'Sul browser gli avvisi compaiono solo a pagina aperta. Installa l’app sul telefono per riceverli sempre.';
  }
  if (!Device.isDevice) return 'Le notifiche push richiedono un telefono reale.';

  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync('avvisi', {
      name: 'Avvisi sul tuo caro',
      importance: Notifications.AndroidImportance.MAX,
      vibrationPattern: [0, 400, 200, 400],
      sound: 'default',
    });
  }
  let { status } = await Notifications.getPermissionsAsync();
  if (status !== 'granted') {
    status = (await Notifications.requestPermissionsAsync()).status;
  }
  if (status !== 'granted') return 'Notifiche non autorizzate: abilitale nelle impostazioni del telefono.';

  const projectId = Constants.expoConfig?.extra?.eas?.projectId as string | undefined;
  if (!projectId) return 'Manca extra.eas.projectId in app.json: esegui "eas init".';
  const { data: token } = await Notifications.getExpoPushTokenAsync({ projectId });
  const { error } = await supabase
    .from('push_tokens')
    .upsert({ token, user_id: userId, updated_at: new Date().toISOString() });
  return error ? error.message : null;
}
