package com.example.sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView t = findViewById(R.id.txt);
        // Reference the package name in several contexts (class + string + authority).
        t.setText("Sample Target\npkg=" + getPackageName()
                + "\nclass=" + MainActivity.class.getName()
                + "\nauthority=com.example.sample.provider");
    }
}
