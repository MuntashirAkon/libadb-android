// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.testapp;

import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;
import io.github.muntashirakon.adb.android.AndroidUtils;

public class MainActivity extends AppCompatActivity {
    private static final int DEFAULT_PORT_ADDRESS = 5555;

    private MaterialButton connectAdbButton;
    private MaterialButton pairAdbButton;
    private MaterialButton runCommandButton;
    private AppCompatEditText commandInput;
    private AppCompatTextView commandOutput;
    private MainViewModel viewModel;
    private boolean connected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        connectAdbButton = findViewById(R.id.connect_adb);
        pairAdbButton = findViewById(R.id.pair_adb);
        runCommandButton = findViewById(R.id.command_run);
        commandInput = findViewById(R.id.command_input);
        commandOutput = findViewById(R.id.command_output);
        init();
    }

    private void init() {
        connectAdbButton.setOnClickListener(v -> {
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
                    .setPositiveButton(R.string.connect, (dialog, which) -> {
                        CharSequence portString = editText.getText();
                        if (portString != null && TextUtils.isDigitsOnly(portString)) {
                            int port = Integer.parseInt(portString.toString());
                            viewModel.connect(port);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            pairAdbButton.setVisibility(View.GONE);
        }
        pairAdbButton.setOnClickListener(v -> {
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
                    .setPositiveButton(R.string.pair, (dialog, which) -> {
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
        });
        runCommandButton.setOnClickListener(v -> {
            String command = Objects.requireNonNull(commandInput.getText()).toString();
            viewModel.execute(command);
        });
        viewModel.watchConnectAdb().observe(this, isConnected -> {
            connected = isConnected;
            if (isConnected) {
                Toast.makeText(this, getString(R.string.connected_to_adb), Toast.LENGTH_SHORT).show();
                connectAdbButton.setText(R.string.disconnect_adb);
            } else {
                Toast.makeText(this, getString(R.string.disconnected_from_adb), Toast.LENGTH_SHORT).show();
                connectAdbButton.setText(R.string.connect_adb);
            }
            runCommandButton.setEnabled(isConnected);
        });
        viewModel.watchPairAdb().observe(this, isPaired -> {
            if (isPaired) {
                Toast.makeText(this, getString(R.string.pairing_successful), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.pairing_failed), Toast.LENGTH_SHORT).show();
            }
        });
        viewModel.watchCommandOutput().observe(this, output ->
                commandOutput.setText(output == null ? "" : output));
        viewModel.autoConnect();
    }

    public static class MainViewModel extends AndroidViewModel {
        private final ExecutorService executor = Executors.newFixedThreadPool(3);
        private final MutableLiveData<Boolean> connectAdb = new MutableLiveData<>();
        private final MutableLiveData<Boolean> pairAdb = new MutableLiveData<>();
        private final MutableLiveData<CharSequence> commandOutput = new MutableLiveData<>();
        private final MutableLiveData<Integer> pairingPort = new MutableLiveData<>();

        @Nullable
        private AdbStream adbShellStream;

        public MainViewModel(@NonNull Application application) {
            super(application);
        }

        public LiveData<Boolean> watchConnectAdb() {
            return connectAdb;
        }

        public LiveData<Boolean> watchPairAdb() {
            return pairAdb;
        }

        public LiveData<CharSequence> watchCommandOutput() {
            return commandOutput;
        }

        public LiveData<Integer> watchPairingPort() {
            return pairingPort;
        }

        @Override
        protected void onCleared() {
            super.onCleared();
            executor.submit(() -> {
                try {
                    if (adbShellStream != null) {
                        adbShellStream.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    AdbConnectionManager.getInstance(getApplication()).close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            executor.shutdown();
        }

        public void connect(int port) {
            executor.submit(() -> {
                try {
                    AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                    boolean connectionStatus;
                    try {
                        connectionStatus = manager.connect(AndroidUtils.getHostIpAddress(getApplication()), port);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        connectionStatus = false;
                    }
                    connectAdb.postValue(connectionStatus);
                } catch (Throwable th) {
                    th.printStackTrace();
                    connectAdb.postValue(false);
                }
            });
        }

        public void autoConnect() {
            executor.submit(this::autoConnectInternal);
        }

        public void disconnect() {
            executor.submit(() -> {
                try {
                    AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                    manager.disconnect();
                    connectAdb.postValue(false);
                } catch (Throwable th) {
                    th.printStackTrace();
                    connectAdb.postValue(true);
                }
            });
        }

        public void getPairingPort() {
            executor.submit(() -> {
                AtomicInteger atomicPort = new AtomicInteger(-1);
                CountDownLatch resolveHostAndPort = new CountDownLatch(1);

                AdbMdns adbMdns = new AdbMdns(getApplication(), AdbMdns.SERVICE_TYPE_TLS_PAIRING, (hostAddress, port) -> {
                    atomicPort.set(port);
                    resolveHostAndPort.countDown();
                });
                adbMdns.start();

                try {
                    if (!resolveHostAndPort.await(1, TimeUnit.MINUTES)) {
                        return;
                    }
                } catch (InterruptedException ignore) {
                } finally {
                    adbMdns.stop();
                }

                pairingPort.postValue(atomicPort.get());
            });
        }

        public void pair(int port, String pairingCode) {
            executor.submit(() -> {
                try {
                    boolean pairingStatus;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                        pairingStatus = manager.pair(AndroidUtils.getHostIpAddress(getApplication()), port, pairingCode);
                    } else pairingStatus = false;
                    pairAdb.postValue(pairingStatus);
                    autoConnectInternal();
                } catch (Throwable th) {
                    th.printStackTrace();
                    pairAdb.postValue(false);
                }
            });
        }

        @WorkerThread
        private void autoConnectInternal() {
            try {
                AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                boolean connectionStatus;
                try {
                    connectionStatus = manager.autoConnect(getApplication());
                } catch (Throwable th) {
                    th.printStackTrace();
                    connectionStatus = false;
                }
                connectAdb.postValue(connectionStatus);
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }

        private volatile boolean clearEnabled;
        private final Runnable outputGenerator = () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(adbShellStream.openInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String s;
                while ((s = reader.readLine()) != null) {
                    if (clearEnabled) {
                        sb.delete(0, sb.length());
                        clearEnabled = false;
                    }
                    sb.append(s).append("\n");
                    commandOutput.postValue(sb);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        };

        private void execute(String command) {
            executor.submit(() -> {
                try {
                    if (adbShellStream == null || adbShellStream.isClosed()) {
                        AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                        adbShellStream = manager.openStream("shell:");
                        new Thread(outputGenerator).start();
                    }
                    if (command.equals("clear")) {
                        clearEnabled = true;
                    }
                    try (OutputStream os = adbShellStream.openOutputStream()) {
                        os.write(String.format("%1$s\n", command).getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        os.write("\n".getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }
}
