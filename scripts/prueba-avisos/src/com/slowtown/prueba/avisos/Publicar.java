package com.slowtown.prueba.avisos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Lo que se le pide por adb. Siempre con -f 0x20 (FLAG_INCLUDE_STOPPED_PACKAGES):
 * recién instalada, la app está detenida y sin eso no recibe nada.
 *
 * <pre>
 * P='am broadcast -n com.slowtown.prueba.avisos/.Publicar -f 0x20'
 * adb shell "$P --es que chat --es chat 120363000000000001@g.us --es de Juan --es texto 'hola grupo'"
 * adb shell "$P --es que chat --es chat 5491100000000@s.whatsapp.net --es de Ana --es texto 'hola'"
 * adb shell "$P --es que leer --es chat 5491100000000@s.whatsapp.net"
 * adb shell "$P --es que resumen"
 * adb shell "$P --es que llamada --es de Ana"
 * adb shell "$P --es que en_curso"
 * adb shell "$P --es que perdida"
 * adb shell "$P --es que cortar"
 * adb shell "$P --es que borrar"
 * </pre>
 *
 * «chat» suma un mensaje al chat (un JID que termina en @g.us es un grupo; el
 * título del grupo, con --es titulo) y publica el chat y después el resumen,
 * como WhatsApp. «leer» saca un chat, como leerlo en otro lado. Las claves de
 * lo publicado vuelven en el resultado del broadcast («data=»).
 */
public class Publicar extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String que = intent.getStringExtra("que");
        String resultado;
        try {
            resultado = hacer(ctx, que == null ? "" : que, intent);
        } catch (Exception e) {
            Log.e(Avisos.TAG, "falló «" + que + "»", e);
            resultado = "error: " + e;
        }
        // Sólo un broadcast ordenado lleva resultado; el de am broadcast lo es.
        if (isOrderedBroadcast()) setResultData(resultado);
    }

    private static String hacer(Context ctx, String que, Intent intent) throws Exception {
        switch (que) {
            case "chat": {
                String jid = intent.getStringExtra("chat");
                if (jid == null || jid.isEmpty()) return "falta --es chat JID";
                boolean grupo = jid.endsWith("@g.us");
                return Avisos.chat(ctx, jid,
                        texto(intent, "de", grupo ? "Juan" : "Ana"),
                        texto(intent, "texto", "Hola"),
                        intent.getStringExtra("titulo"));
            }
            case "leer": {
                String jid = intent.getStringExtra("chat");
                if (jid == null || jid.isEmpty()) return "falta --es chat JID";
                return Avisos.leer(ctx, jid);
            }
            case "resumen":
                return Avisos.resumen(ctx);
            case "llamada":
                return Avisos.llamada(ctx, texto(intent, "de", "Ana"));
            case "en_curso":
                return Avisos.enCurso(ctx);
            case "perdida":
                return Avisos.perdida(ctx);
            case "cortar":
                return Avisos.cortar(ctx);
            case "borrar":
                return Avisos.borrar(ctx);
            default:
                return "¿qué? chat, leer, resumen, llamada, en_curso, perdida, cortar o borrar";
        }
    }

    private static String texto(Intent intent, String extra, String siNo) {
        String v = intent.getStringExtra(extra);
        return v == null || v.isEmpty() ? siNo : v;
    }
}
