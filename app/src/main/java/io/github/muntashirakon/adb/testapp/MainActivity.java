// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.testapp;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    private static final int DEFAULT_PORT_ADDRESS = 5555;

    private MenuItem connectAdbMenu;
    private MenuItem disconnectAdbMenu;
    private MenuItem pairAdbMenu;
    private MenuItem developerModeMenu;
    private ScrollView scrollView;

    private AppCompatEditText commandInput;
    private AppCompatTextView commandOutput;
    private MainViewModel viewModel;
    private boolean connected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        scrollView = findViewById(R.id.scrollView);
        commandInput = findViewById(R.id.command_input);
        commandOutput = findViewById(R.id.command_output);
        init();
    }

    private void init() {
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && connected) {
                String command = Objects.requireNonNull(commandInput.getText()).toString();
                viewModel.execute(command);
                return true;
            }
            return false;
        });

        // Observers
        viewModel.watchConnectAdb().observe(this, isConnected -> {
            connected = isConnected;
            checkMenus();
            if (isConnected) {
                Toast.makeText(this, getString(R.string.connected_to_adb), Toast.LENGTH_SHORT).show();
                openIme();
            } else {
                Toast.makeText(this, getString(R.string.disconnected_from_adb), Toast.LENGTH_SHORT).show();
            }
        });
        viewModel.watchPairAdb().observe(this, isPaired -> {
            if (isPaired) {
                Toast.makeText(this, getString(R.string.pairing_successful), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.pairing_failed), Toast.LENGTH_SHORT).show();
            }
        });
        viewModel.watchAskPairAdb().observe(this, displayDialog -> {
            if (displayDialog) {
                pairAdb();
            }
        });
        viewModel.watchCommandOutput().observe(this, output -> {
            commandOutput.setText(output == null ? "" : output);
            commandOutput.post(() -> scrollView.scrollTo(0, commandOutput.getHeight()));
        });

        // Try auto-connecting
        viewModel.autoConnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        openIme();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (connected && commandInput != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(commandInput.getWindowToken(), 0);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.actions_main, menu);
        connectAdbMenu = menu.findItem(R.id.action_connect);
        disconnectAdbMenu = menu.findItem(R.id.action_disconnect);
        pairAdbMenu = menu.findItem(R.id.action_pair);
        developerModeMenu = menu.findItem(R.id.action_developer_mode);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        checkMenus();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_connect) {
            connectAdb();
            return true;
        }
        if (id == R.id.action_disconnect) {
            connectAdb();
            return true;
        }
        if (id == R.id.action_pair) {
            pairAdb();
            return true;
        }
        if (id == R.id.action_developer_mode) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openIme() {
        if (connected && commandInput != null && !commandInput.isFocused()) {
            commandInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(commandInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void checkMenus() {
        if (connectAdbMenu != null) {
            connectAdbMenu.setEnabled(!connected);
            connectAdbMenu.setVisible(!connected);
        }
        if (disconnectAdbMenu != null) {
            disconnectAdbMenu.setEnabled(connected);
            disconnectAdbMenu.setVisible(connected);
        }

        boolean visible = !connected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;

        if (pairAdbMenu != null) {
            pairAdbMenu.setEnabled(visible);
            pairAdbMenu.setVisible(visible);
        }

        if (developerModeMenu != null) {
            developerModeMenu.setEnabled(visible);
            developerModeMenu.setVisible(visible);
        }
    }

    private void connectAdb() {
        if (connected) {
            viewModel.disconnect();
            return;
        }
        AppCompatEditText editText = new AppCompatEditText(this);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setText(String.valueOf(DEFAULT_PORT_ADDRESS));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.connect_adb)
                .setView(editText)
                .setPositiveButton(R.string.connect_adb, (dialog, which) -> {
                    CharSequence portString = editText.getText();
                    if (portString != null && TextUtils.isDigitsOnly(portString)) {
                        int port = Integer.parseInt(portString.toString());
                        viewModel.connect(port);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pairAdb() {
        View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
        TextInputEditText pairingCodeEditText = view.findViewById(R.id.pairing_code);
        TextInputEditText portNumberEditText = view.findViewById(R.id.port_number);
        viewModel.watchPairingPort().observe(this, port -> {
            if (port != -1) {
                portNumberEditText.setText(String.valueOf(port));
            } else {
                portNumberEditText.setText(null);
            }
        });
        viewModel.getPairingPort();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pair_adb)
                .setView(view)
                .setPositiveButton(R.string.pair_adb, (dialog, which) -> {
                    CharSequence pairingCode = pairingCodeEditText.getText();
                    CharSequence portNumberString = portNumberEditText.getText();
                    if (pairingCode != null && pairingCode.length() == 6 && portNumberString != null
                            && TextUtils.isDigitsOnly(portNumberString)) {
                        int port = Integer.parseInt(portNumberString.toString());
                        viewModel.pair(port, pairingCode.toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(dialog -> viewModel.watchPairingPort().removeObservers(this))
                .show();
    }
}
