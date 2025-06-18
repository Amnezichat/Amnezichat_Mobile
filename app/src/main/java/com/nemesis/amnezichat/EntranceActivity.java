package com.nemesis.amnezichat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.jcajce.provider.digest.SHA3.Digest512;

public class EntranceActivity extends AppCompatActivity {

    private static final int PICK_WALLPAPER_REQUEST_CODE = 1001;
    private static final String TAG = "KeyDerivation";

    private Button createRoomButton, joinRoomButton, submitButton, pickWallpaperButton, forgetEverythingButton;
    private TextView generatedRoomIdLabel, errorMessageText;
    private EditText roomIdInput, serverUrlInput, usernameInput, roomPasswordInput;
    private CheckBox rememberSettingsCheckbox, daitaCheckbox;

    private String choice = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entrance);

        createRoomButton = findViewById(R.id.createRoomButton);
        joinRoomButton = findViewById(R.id.joinRoomButton);
        submitButton = findViewById(R.id.submitButton);
        pickWallpaperButton = findViewById(R.id.pickWallpaperButton);
        forgetEverythingButton = findViewById(R.id.forgetEverythingButton);
        generatedRoomIdLabel = findViewById(R.id.generatedRoomIdLabel);
        errorMessageText = findViewById(R.id.errorMessageText);

        roomIdInput = findViewById(R.id.roomIdInput);
        serverUrlInput = findViewById(R.id.serverUrlInput);
        usernameInput = findViewById(R.id.usernameInput);
        roomPasswordInput = findViewById(R.id.roomPasswordInput);
        rememberSettingsCheckbox = findViewById(R.id.rememberSettingsCheckbox);
        daitaCheckbox = findViewById(R.id.daitaCheckbox);

        SharedPreferences prefs = getSharedPreferences("ChatPrefs", MODE_PRIVATE);
        boolean rememberSettings = prefs.getBoolean("remember_settings", false);
        rememberSettingsCheckbox.setChecked(rememberSettings);
        daitaCheckbox.setChecked(prefs.getBoolean("daita_enabled", false));

        if (rememberSettings) {
            serverUrlInput.setText(prefs.getString("server_url", ""));
            usernameInput.setText(prefs.getString("username", ""));
            roomIdInput.setText(prefs.getString("room_id", ""));
            roomPasswordInput.setText(prefs.getString("room_password", ""));

            if (!roomIdInput.getText().toString().isEmpty()) {
                roomIdInput.setVisibility(View.VISIBLE);
                generatedRoomIdLabel.setVisibility(View.GONE);
                choice = "join";
            } else {
                roomIdInput.setVisibility(View.GONE);
                generatedRoomIdLabel.setVisibility(View.GONE);
                choice = "";
            }
        } else {
            roomIdInput.setVisibility(View.GONE);
            generatedRoomIdLabel.setVisibility(View.GONE);
            choice = "";
        }

        roomPasswordInput.setVisibility(View.VISIBLE);

        createRoomButton.setOnClickListener(v -> {
            choice = "create";
            roomIdInput.setVisibility(View.GONE);
            generatedRoomIdLabel.setVisibility(View.VISIBLE);
            String generatedId = generateRandomRoomId();
            generatedRoomIdLabel.setText("Generated Room ID: " + generatedId);
            roomIdInput.setText(generatedId);
            errorMessageText.setVisibility(View.GONE);
        });

        joinRoomButton.setOnClickListener(v -> {
            choice = "join";
            roomIdInput.setVisibility(View.VISIBLE);
            generatedRoomIdLabel.setVisibility(View.GONE);
            roomIdInput.setText("");
            errorMessageText.setVisibility(View.GONE);
        });

        pickWallpaperButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_WALLPAPER_REQUEST_CODE);
        });

        forgetEverythingButton.setOnClickListener(v -> {
            SharedPreferences.Editor editor = getSharedPreferences("ChatPrefs", MODE_PRIVATE).edit();
            editor.clear();
            editor.apply();

            Toast.makeText(this, "All data forgotten!", Toast.LENGTH_SHORT).show();

            roomIdInput.setText("");
            serverUrlInput.setText("");
            usernameInput.setText("");
            roomPasswordInput.setText("");
            rememberSettingsCheckbox.setChecked(false);
            daitaCheckbox.setChecked(false);
            roomIdInput.setVisibility(View.GONE);
            generatedRoomIdLabel.setVisibility(View.GONE);
            errorMessageText.setVisibility(View.GONE);
            choice = "";
        });

        submitButton.setOnClickListener(v -> {
            errorMessageText.setVisibility(View.GONE);

            String serverUrl = serverUrlInput.getText().toString().trim();
            String username = usernameInput.getText().toString().trim();
            String roomId = roomIdInput.getText().toString().trim();
            String roomPassword = roomPasswordInput.getText().toString().trim();
            boolean daitaEnabled = daitaCheckbox.isChecked();

            if (serverUrl.isEmpty() || username.isEmpty()) {
                showError("Please fill in Server URL and Username.");
                return;
            }

            if (roomPassword.length() < 8) {
                showError("Room password must be at least 8 characters.");
                return;
            }

            if (choice.isEmpty()) {
                showError("Please select Create Room or Join Room.");
                return;
            }

            if (choice.equals("join") && roomId.isEmpty()) {
                showError("Please enter a Room ID to join.");
                return;
            }

            String encryptionKeyHex;
            try {
                encryptionKeyHex = deriveKeyFromPassword(roomPassword);
            } catch (Exception e) {
                showError("Failed to derive encryption key.");
                return;
            }

            SharedPreferences.Editor editor = getSharedPreferences("ChatPrefs", MODE_PRIVATE).edit();
            if (rememberSettingsCheckbox.isChecked()) {
                editor.putBoolean("remember_settings", true);
                editor.putString("server_url", serverUrl);
                editor.putString("username", username);
                editor.putString("room_id", roomId);
                editor.putString("room_password", roomPassword);
                editor.putBoolean("daita_enabled", daitaEnabled);
            } else {
                editor.clear();
            }
            editor.apply();

            Toast.makeText(this, "Setup successful! Starting chat...", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(EntranceActivity.this, MainActivity.class);
            intent.putExtra("server_url", serverUrl);
            intent.putExtra("username", username);
            intent.putExtra("room_id", roomId);
            intent.putExtra("encryption_key", encryptionKeyHex);
            intent.putExtra("daita_enabled", String.valueOf(daitaEnabled));
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_WALLPAPER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri selectedImageUri = data.getData();
            if (selectedImageUri != null) {
                try {
                    final int takeFlags = data.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(selectedImageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    SharedPreferences.Editor editor = getSharedPreferences("ChatPrefs", MODE_PRIVATE).edit();
                    editor.putString("wallpaper_uri", selectedImageUri.toString());
                    editor.apply();

                    Toast.makeText(this, "Wallpaper selected!", Toast.LENGTH_SHORT).show();
                } catch (SecurityException e) {
                    Toast.makeText(this, "Permission denied for wallpaper", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void showError(String msg) {
        errorMessageText.setText(msg);
        errorMessageText.setVisibility(View.VISIBLE);
    }

    private String generateRandomRoomId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String deriveKeyFromPassword(String password) {
        try {
            Digest512 sha3 = new Digest512();
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            byte[] fullHash = sha3.digest(passwordBytes);

            byte[] salt = Arrays.copyOf(fullHash, 16);
            Log.d(TAG, "Salt generated for key derivation");

            Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withSalt(salt)
                    .withIterations(2)
                    .withMemoryAsKB(19456)
                    .withParallelism(1)
                    .build();

            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);
            byte[] key = new byte[32];
            generator.generateBytes(passwordBytes, key);

            Log.d(TAG, "Derived key generated successfully");
            return bytesToHex(key);

        } catch (Exception e) {
            Log.e(TAG, "Key derivation failed", e);
            throw new RuntimeException("Key derivation failed: " + e.getMessage(), e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
