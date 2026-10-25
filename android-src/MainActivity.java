package fr.aflament.sillon;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SillonPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
