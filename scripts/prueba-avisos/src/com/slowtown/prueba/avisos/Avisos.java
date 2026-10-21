package com.slowtown.prueba.avisos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lo que publica la app, armado como lo arma WhatsApp: una notificación por
 * chat (tag y atajo con el JID del chat), todas en un solo grupo con un
 * resumen, y cada vez primero el chat y después el resumen. Ese grupo único
 * es lo que complica posponer: Android pospone el resumen junto con su grupo.
 *
 * La llamada imita una de WhatsApp: categoría de llamada, en curso, con
 * pantalla completa y los botones «Responder» y «Cortar».
 */
final class Avisos {
    static final String TAG = "AvisosPrueba";

    private static final String GRUPO = "mensajes";
    // Los chats y el resumen comparten el id: los separa el tag (el JID; el resumen va sin tag).
    private static final int ID_CHAT = 1;
    private static final int ID_RESUMEN = 1;
    private static final int ID_LLAMADA = 2;
    private static final String TAG_LLAMADA = "llamada";

    private static final String CANAL_MENSAJES = "mensajes";
    private static final String CANAL_LLAMADAS = "llamadas";
    private static final String CANAL_EN_CURSO = "en_curso";
    private static final String CANAL_PERDIDAS = "perdidas";

    private static final String JID_GRUPO = "@g.us";
    private static final String PREFS_CHATS = "chats";
    private static final String PREFS_LLAMADA = "llamada";
    private static final int MAX_MENSAJES = 25;

    private Avisos() {
    }

    /** Suma un mensaje al chat y publica el chat y después el resumen. Devuelve las dos claves. */
    static String chat(Context ctx, String jid, String de, String texto, String titulo) throws JSONException {
        canales(ctx);
        boolean grupo = jid.endsWith(JID_GRUPO);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_CHATS, Context.MODE_PRIVATE);
        JSONObject chat = new JSONObject(prefs.getString(jid, "{}"));
        if (titulo != null) chat.put("titulo", titulo);
        if (!chat.has("titulo")) chat.put("titulo", grupo ? "Grupo de prueba" : de);
        JSONArray mensajes = chat.optJSONArray("mensajes");
        if (mensajes == null) mensajes = new JSONArray();
        JSONObject mensaje = new JSONObject();
        mensaje.put("de", de);
        mensaje.put("texto", texto);
        mensaje.put("cuando", System.currentTimeMillis());
        mensajes.put(mensaje);
        while (mensajes.length() > MAX_MENSAJES) mensajes.remove(0);
        chat.put("mensajes", mensajes);
        // commit y no apply: el proceso puede morir apenas termina el broadcast.
        prefs.edit().putString(jid, chat.toString()).commit();

        nm(ctx).notify(jid, ID_CHAT, armarChat(ctx, jid, grupo, chat));
        Log.i(TAG, "chat " + jid + ": " + mensajes.length() + " mensajes");
        return "chat=" + clave(ctx, ID_CHAT, jid) + " " + resumen(ctx);
    }

    /** Como leer el chat en otro lado: WhatsApp lo saca y rearma el resumen. */
    static String leer(Context ctx, String jid) throws JSONException {
        ctx.getSharedPreferences(PREFS_CHATS, Context.MODE_PRIVATE).edit().remove(jid).commit();
        nm(ctx).cancel(jid, ID_CHAT);
        Log.i(TAG, "leído " + jid);
        return resumen(ctx);
    }

    /** El resumen con lo que hay: «N mensajes de M chats». Sin chats, se cancela. */
    static String resumen(Context ctx) throws JSONException {
        canales(ctx);
        List<JSONObject> chats = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, ?> e : ctx.getSharedPreferences(PREFS_CHATS, Context.MODE_PRIVATE).getAll().entrySet()) {
            JSONObject chat = new JSONObject(String.valueOf(e.getValue()));
            chat.put("jid", e.getKey());
            JSONArray mensajes = chat.optJSONArray("mensajes");
            if (mensajes == null || mensajes.length() == 0) continue;
            total += mensajes.length();
            chats.add(chat);
        }
        if (chats.isEmpty()) {
            nm(ctx).cancel(null, ID_RESUMEN);
            Log.i(TAG, "resumen cancelado");
            return "resumen=cancelado";
        }
        // El chat con el último mensaje, arriba.
        Collections.sort(chats, (a, b) -> Long.compare(ultimo(b).optLong("cuando"), ultimo(a).optLong("cuando")));
        String texto = total + (total == 1 ? " mensaje" : " mensajes") + " de "
                + chats.size() + (chats.size() == 1 ? " chat" : " chats");
        Notification.InboxStyle inbox = new Notification.InboxStyle().setSummaryText(texto);
        for (JSONObject chat : chats) {
            JSONObject m = ultimo(chat);
            boolean grupo = chat.getString("jid").endsWith(JID_GRUPO);
            inbox.addLine((grupo ? chat.optString("titulo") + ": " : "") + m.optString("de") + ": " + m.optString("texto"));
        }
        Notification n = new Notification.Builder(ctx, CANAL_MENSAJES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Avisos de prueba")
                .setContentText(texto)
                .setStyle(inbox)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setGroup(GRUPO)
                .setGroupSummary(true)
                // Suenan los chats, no el resumen.
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setContentIntent(abrir(ctx))
                .setAutoCancel(true)
                .build();
        nm(ctx).notify(null, ID_RESUMEN, n);
        Log.i(TAG, "resumen: " + texto);
        return "resumen=" + clave(ctx, ID_RESUMEN, null);
    }

    /** Una llamada que suena: de pantalla completa, en curso, con «Responder» y «Cortar». */
    static String llamada(Context ctx, String de) {
        canales(ctx);
        ctx.getSharedPreferences(PREFS_LLAMADA, Context.MODE_PRIVATE).edit()
                .putString("de", de).putLong("desde", 0).commit();
        PendingIntent pantalla = PendingIntent.getActivity(ctx, 1,
                new Intent(ctx, Principal.class).putExtra("que", "llamada").putExtra("de", de)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent responder = PendingIntent.getActivity(ctx, 2,
                new Intent(ctx, Principal.class).putExtra("que", "atender").putExtra("de", de)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, CANAL_LLAMADAS)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(de)
                .setContentText("Llamada de voz entrante")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setFullScreenIntent(pantalla, true)
                .setContentIntent(pantalla)
                .addAction(accion(ctx, android.R.drawable.ic_menu_close_clear_cancel, "Cortar", alCortar(ctx)))
                .addAction(accion(ctx, android.R.drawable.ic_menu_call, "Responder", responder))
                .build();
        nm(ctx).notify(TAG_LLAMADA, ID_LLAMADA, n);
        Log.i(TAG, "llamada de " + de + " sonando");
        return "llamada=" + clave(ctx, ID_LLAMADA, TAG_LLAMADA);
    }

    /** La misma llamada, atendida: en curso, sin sonido ni pantalla completa, con «Cortar». */
    static String enCurso(Context ctx) {
        canales(ctx);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_LLAMADA, Context.MODE_PRIVATE);
        String de = prefs.getString("de", "Ana");
        long desde = prefs.getLong("desde", 0);
        if (desde == 0) {
            desde = System.currentTimeMillis();
            prefs.edit().putLong("desde", desde).commit();
        }
        PendingIntent pantalla = PendingIntent.getActivity(ctx, 4,
                new Intent(ctx, Principal.class).putExtra("que", "en_curso")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, CANAL_EN_CURSO)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(de)
                .setContentText("Llamada en curso")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(desde)
                .setShowWhen(true)
                .setContentIntent(pantalla)
                .addAction(accion(ctx, android.R.drawable.ic_menu_close_clear_cancel, "Cortar", alCortar(ctx)))
                .build();
        nm(ctx).notify(TAG_LLAMADA, ID_LLAMADA, n);
        Log.i(TAG, "llamada con " + de + " en curso");
        return "llamada=" + clave(ctx, ID_LLAMADA, TAG_LLAMADA);
    }

    /** La que sonaba y nadie atendió: la misma notificación queda como llamada perdida, que se puede descartar. */
    static String perdida(Context ctx) {
        canales(ctx);
        String de = ctx.getSharedPreferences(PREFS_LLAMADA, Context.MODE_PRIVATE).getString("de", "Ana");
        Notification n = new Notification.Builder(ctx, CANAL_PERDIDAS)
                .setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle(de)
                .setContentText("Llamada de voz perdida")
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setContentIntent(abrir(ctx))
                .setAutoCancel(true)
                .build();
        nm(ctx).notify(TAG_LLAMADA, ID_LLAMADA, n);
        Log.i(TAG, "llamada de " + de + " perdida");
        return "llamada=" + clave(ctx, ID_LLAMADA, TAG_LLAMADA);
    }

    static String cortar(Context ctx) {
        nm(ctx).cancel(TAG_LLAMADA, ID_LLAMADA);
        ctx.getSharedPreferences(PREFS_LLAMADA, Context.MODE_PRIVATE).edit().clear().commit();
        Log.i(TAG, "llamada cortada");
        return "llamada=cortada";
    }

    /** Todo afuera: las notificaciones y lo que se recordaba de los chats. */
    static String borrar(Context ctx) {
        nm(ctx).cancelAll();
        ctx.getSharedPreferences(PREFS_CHATS, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(PREFS_LLAMADA, Context.MODE_PRIVATE).edit().clear().commit();
        Log.i(TAG, "todo borrado");
        return "borrado";
    }

    private static Notification armarChat(Context ctx, String jid, boolean grupo, JSONObject chat) throws JSONException {
        JSONArray mensajes = chat.getJSONArray("mensajes");
        String titulo = chat.optString("titulo");
        Notification.MessagingStyle estilo =
                new Notification.MessagingStyle(new Person.Builder().setName("Yo").setKey("yo").build());
        if (grupo) {
            estilo.setConversationTitle(titulo);
            estilo.setGroupConversation(true);
        }
        for (int i = 0; i < mensajes.length(); i++) {
            JSONObject m = mensajes.getJSONObject(i);
            Person de = new Person.Builder().setName(m.optString("de")).setKey(m.optString("de")).build();
            estilo.addMessage(m.optString("texto"), m.optLong("cuando"), de);
        }
        JSONObject ultimo = mensajes.getJSONObject(mensajes.length() - 1);
        return new Notification.Builder(ctx, CANAL_MENSAJES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(grupo ? titulo : ultimo.optString("de"))
                .setContentText(grupo ? ultimo.optString("de") + ": " + ultimo.optString("texto") : ultimo.optString("texto"))
                .setStyle(estilo)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setGroup(GRUPO)
                .setShortcutId(jid)
                .setNumber(mensajes.length())
                .setWhen(ultimo.optLong("cuando"))
                .setShowWhen(true)
                .setContentIntent(abrir(ctx))
                .setAutoCancel(true)
                .build();
    }

    private static JSONObject ultimo(JSONObject chat) {
        JSONArray mensajes = chat.optJSONArray("mensajes");
        JSONObject m = mensajes == null ? null : mensajes.optJSONObject(mensajes.length() - 1);
        return m == null ? new JSONObject() : m;
    }

    private static void canales(Context ctx) {
        NotificationManager nm = nm(ctx);
        NotificationChannel mensajes =
                new NotificationChannel(CANAL_MENSAJES, "Mensajes", NotificationManager.IMPORTANCE_HIGH);
        mensajes.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        mensajes.enableVibration(true);
        nm.createNotificationChannel(mensajes);
        NotificationChannel llamadas =
                new NotificationChannel(CANAL_LLAMADAS, "Llamadas entrantes", NotificationManager.IMPORTANCE_HIGH);
        llamadas.setSound(Settings.System.DEFAULT_RINGTONE_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        nm.createNotificationChannel(llamadas);
        nm.createNotificationChannel(
                new NotificationChannel(CANAL_EN_CURSO, "Llamada en curso", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(
                new NotificationChannel(CANAL_PERDIDAS, "Llamadas perdidas", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static PendingIntent abrir(Context ctx) {
        return PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, Principal.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent alCortar(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 3,
                new Intent(ctx, Publicar.class).putExtra("que", "cortar"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification.Action accion(Context ctx, int icono, String texto, PendingIntent pi) {
        return new Notification.Action.Builder(Icon.createWithResource(ctx, icono), texto, pi).build();
    }

    private static NotificationManager nm(Context ctx) {
        return ctx.getSystemService(NotificationManager.class);
    }

    /** La clave con que Android y los lectores nombran la notificación: usuario|paquete|id|tag|uid. */
    private static String clave(Context ctx, int id, String tag) {
        int uid = Process.myUid();
        return (uid / 100_000) + "|" + ctx.getPackageName() + "|" + id + "|" + tag + "|" + uid;
    }
}
