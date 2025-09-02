package com.ld.ainote;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.ld.ainote.fragments.AiFragment;
import com.ld.ainote.fragments.NotesFragment;
import com.ld.ainote.fragments.ProfileFragment;
import com.ld.ainote.fragments.OcrFragment;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loadFragment(new AiFragment());

        BottomNavigationView nav = findViewById(R.id.bottom_navigation);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment selected;
            if (id == R.id.nav_home) {
                selected = new AiFragment();
            } else if (id == R.id.nav_notes) {
                selected = new NotesFragment();
            } else if (id == R.id.nav_profile) {
                selected = new ProfileFragment();
            } else if (id == R.id.nav_ocr) {
                selected = new OcrFragment();
            } else {
                selected = new AiFragment();
            }
            loadFragment(selected);
            return true;
        });
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}
