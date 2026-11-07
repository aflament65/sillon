package fr.aflament.sillon;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SillonPlugin.class);
        super.onCreate(savedInstanceState);
        /* l'appli garde sa priorité quand elle passe en arrière-plan ou que l'écran s'éteint */
        if (Build.VERSION.SDK_INT >= 26) {
            WebView w = getBridge().getWebView();
            if (w != null) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        /* tâche en cours : on n'endort pas la page (minuteries et calculs continuent écran éteint) */
        if (SillonPlugin.WORKING) {
            WebView w = getBridge().getWebView();
            if (w != null) { w.onResume(); w.resumeTimers(); }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (SillonPlugin.WORKING) {
            WebView w = getBridge().getWebView();
            if (w != null) { w.onResume(); w.resumeTimers(); }
        }
    }
}
