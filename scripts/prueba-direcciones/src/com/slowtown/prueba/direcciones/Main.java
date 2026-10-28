package com.slowtown.prueba.direcciones;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Direcciones de prueba», para el paso 16 de probar-emulador.sh: una
 * pantalla con una barra de direcciones (url_bar) que muestra la dirección que
 * le pasan, como un navegador cuando no se escribe en la barra. La variante de
 * prueba de Slowtown la mira como a Chrome.
 *
 *   am start -n com.slowtown.prueba.direcciones/.Main --es url https://m.youtube.com/watch?v=x
 *     --ez escribiendo true   la barra con el foco, como mientras se escribe
 *     --ez terco true         Atrás no hace nada, como una pestaña que empezó en esa página
 */
public class Main extends Activity {
    private LinearLayout page;
    private EditText bar;
    private boolean stubborn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // La página se queda con el foco: la barra lo toma sólo «escribiendo».
        page.setFocusable(true);
        page.setFocusableInTouchMode(true);
        bar = new EditText(this);
        bar.setId(R.id.url_bar);
        bar.setSingleLine(true);
        page.addView(bar);
        TextView text = new TextView(this);
        text.setText("Direcciones de prueba");
        page.addView(text);
        setContentView(page);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        String url = intent.getStringExtra("url");
        if (url == null && intent.getData() != null) {
            url = intent.getData().toString();
        }
        bar.setText(url == null ? "" : url);
        stubborn = intent.getBooleanExtra("terco", false);
        if (intent.getBooleanExtra("escribiendo", false)) {
            bar.requestFocus();
        } else {
            page.requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        if (!stubborn) {
            super.onBackPressed();
        }
    }
}
