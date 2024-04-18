// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.testapp;

import android.app.Application;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.LocalServices;
import io.github.muntashirakon.adb.android.AdbMdns;
import io.github.muntashirakon.adb.android.AndroidUtils;

public class MainViewModel extends AndroidViewModel {
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final MutableLiveData<Boolean> connectAdb = new MutableLiveData<>();
    private final MutableLiveData<Boolean> pairAdb = new MutableLiveData<>();
    private final MutableLiveData<Boolean> askPairAdb = new MutableLiveData<>();
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

    public LiveData<Boolean> watchAskPairAdb() {
        return askPairAdb;
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
            boolean connected = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    connected = manager.autoConnect(getApplication(), 5000);
                } catch (AdbPairingRequiredException e) {
                    askPairAdb.postValue(true);
                    return;
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
            if (!connected) {
                connected = manager.connect(5555);
            }
            if (connected) {
                connectAdb.postValue(true);
            }
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

    public void execute(String command) {
        executor.submit(() -> {
            try {
                if (adbShellStream == null || adbShellStream.isClosed()) {
                    AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                    adbShellStream = manager.openStream(LocalServices.SHELL);
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
