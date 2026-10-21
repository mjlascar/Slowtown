package com.slowtown.prueba.avisos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * La pantalla de la app: la de la llamada (la abre la pantalla completa de la
 * notificación o su botón «Responder») y, si no, un cartel. Atender cambia la
 * notificación por la de llamada en curso, como una app de llamadas.
 */
public class Principal extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mostrar(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mostrar(intent);
    }

    private void mostrar(Intent intent) {
        String que = intent.getStringExtra("que");
        String de = intent.getStringExtra("de");
        if (de == null) de = "Ana";
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setGravity(Gravity.CENTER);
        raiz.setPadding(48, 48, 48, 48);
        TextView texto = new TextView(this);
        texto.setTextSize(24);
        texto.setGravity(Gravity.CENTER);
        raiz.addView(texto);
        if ("atender".equals(que)) {
            Avisos.enCurso(this);
            texto.setText("En llamada con " + de);
            raiz.addView(boton("Cortar", () -> {
                Avisos.cortar(this);
                finish();
            }));
        } else if ("en_curso".equals(que)) {
            texto.setText("En llamada");
            raiz.addView(boton("Cortar", () -> {
                Avisos.cortar(this);
                finish();
            }));
        } else if ("llamada".equals(que)) {
            String quien = de;
            texto.setText("Llamada entrante de " + de);
            raiz.addView(boton("Responder", () -> mostrar(new Intent().putExtra("que", "atender").putExtra("de", quien))));
            raiz.addView(boton("Cortar", () -> {
                Avisos.cortar(this);
                finish();
            }));
        } else {
            texto.setText("Avisos de prueba\n\nSe maneja por adb: mirá Publicar.java.");
        }
        setContentView(raiz);
    }

    private Button boton(String texto, Runnable accion) {
        Button b = new Button(this);
        b.setText(texto);
        b.setOnClickListener(v -> accion.run());
        return b;
    }
}
