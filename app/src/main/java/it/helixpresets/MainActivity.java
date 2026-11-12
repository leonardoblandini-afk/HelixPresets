package it.helixpresets;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import it.helixpresets.protocol.HelixConnection;
import it.helixpresets.protocol.Preset;

public class MainActivity extends Activity {
    private static final int LINE6_VENDOR_ID = 0x0E41;
    private static final String ACTION_PERMISSION = "it.helixpresets.USB_PERMISSION";

    private UsbManager usb;
    private final List<Preset> presets = new ArrayList<>();
    private PresetAdapter adapter;
    private TextView status, logView;
    private Button button;
    private volatile Preset current;
    private final StringBuilder log = new StringBuilder();

    // Tutto il traffico USB passa da questo unico thread: richieste, selezioni e lettura degli eventi.
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private HelixConnection helix;
    private UsbDeviceConnection usbConn;
    private UsbInterface usbIface;
    private ScheduledFuture<?> poller;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connectTo(d);
            else setStatus("Permesso USB negato");
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        status = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        button = findViewById(R.id.connect);
        ListView list = findViewById(R.id.presets);

        adapter = new PresetAdapter(this, presets, new PresetAdapter.Listener() {
            @Override public void onPreset(Preset p) { select(p, 0); }
            @Override public void onSnapshot(Preset p, int snapshot) { select(p, snapshot); }
        });
        list.setAdapter(adapter);
        button.setOnClickListener(v -> connect());
        findViewById(R.id.disconnect).setOnClickListener(v -> disconnect());
        findViewById(R.id.toggleLog).setOnClickListener(v ->
                logView.setVisibility(logView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permissionReceiver, f);

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) connect();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(permissionReceiver);
        io.execute(this::closeAll);
        io.shutdown();
        super.onDestroy();
    }

    private void connect() {
        UsbDevice found = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == LINE6_VENDOR_ID) { found = d; break; }
        }
        if (found == null) { setStatus("Nessun dispositivo Line 6 collegato"); return; }
        if (usb.hasPermission(found)) { connectTo(found); return; }
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
        usb.requestPermission(found, PendingIntent.getBroadcast(this, 0, i, flags));
        setStatus("Richiesta permesso USB…");
    }

    private void connectTo(final UsbDevice device) {
        runOnUiThread(() -> { button.setEnabled(false); log.setLength(0); });
        setStatus("Lettura dei preset da " + device.getProductName() + "…");
        io.execute(() -> {
            closeAll();
            try {
                usbConn = usb.openDevice(device);
                if (usbConn == null) throw new Exception("openDevice ha restituito null");
                UsbEndpoint in = null, out = null;
                for (int k = 0; k < device.getInterfaceCount(); k++) {
                    UsbInterface itf = device.getInterface(k);
                    UsbEndpoint ei = null, eo = null;
                    for (int e = 0; e < itf.getEndpointCount(); e++) {
                        UsbEndpoint ep = itf.getEndpoint(e);
                        addLog(String.format("interfaccia %d endpoint 0x%02x tipo %d", k, ep.getAddress(), ep.getType()));
                        boolean transfer = ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                                || ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT;
                        if (!transfer || (ep.getAddress() & 0x0f) != 1) continue; // la cattura usa l'endpoint 1
                        if (ep.getDirection() == UsbConstants.USB_DIR_IN) ei = ep; else eo = ep;
                    }
                    if (ei != null && eo != null) { usbIface = itf; in = ei; out = eo; break; }
                }
                if (usbIface == null) throw new Exception("interfaccia con endpoint 1 IN/OUT non trovata");
                if (!usbConn.claimInterface(usbIface, true)) throw new Exception("claimInterface fallita");

                helix = new HelixConnection(new UsbTransport(usbConn, in, out), this::addLog);
                final List<Preset> result = helix.connect();
                runOnUiThread(() -> {
                    presets.clear();
                    presets.addAll(result);
                    adapter.setSelected(-1);
                });
                setStatus(result.size() + " preset letti. Tocca un preset per selezionarlo e leggere i suoi snapshot.");
                poller = io.scheduleWithFixedDelay(this::pollEvents, 500, 300, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                setStatus("Errore: " + e.getMessage());
                closeAll();
            } finally {
                runOnUiThread(() -> button.setEnabled(true));
            }
        });
    }

    /**
     * Tocco su un preset (snapshot = 0) o su un bottone snapshot (1..3). Il preset viene selezionato sulla HX Stomp
     * e si leggono i nomi dei suoi snapshot; con un bottone snapshot si passa poi a quello snapshot.
     */
    private void select(final Preset p, final int snapshot) {
        if (helix == null) { setStatus("Non collegato"); return; }
        setStatus("Seleziono " + p + "…");
        io.execute(() -> {
            try {
                if (helix == null) return;
                if (current != p) {
                    HelixConnection.PresetInfo info = helix.select(p.position);
                    p.snapshots = info.snapshots;
                    p.activeSnapshot = info.snapshot;
                    current = p;
                    final int index = presets.indexOf(p);
                    runOnUiThread(() -> adapter.setSelected(index));
                }
                if (snapshot == 0) setStatus("Selezionato: " + p);
                else {
                    if (p.activeSnapshot != snapshot) {
                        p.activeSnapshot = helix.selectSnapshot(snapshot);
                        runOnUiThread(() -> adapter.notifyDataSetChanged());
                    }
                    setStatus("Snapshot " + snapshot + " di " + p);
                }
            } catch (Exception e) {
                setStatus("Preset " + p + ": " + e.getMessage());
            }
        });
    }

    private void disconnect() {
        io.execute(() -> {
            closeAll();
            runOnUiThread(() -> { presets.clear(); adapter.setSelected(-1); });
            setStatus("Scollegato");
        });
    }

    private void pollEvents() {
        try {
            if (helix != null) helix.poll(30);
        } catch (Exception e) {
            setStatus("Connessione persa: " + e.getMessage());
            closeAll();
        }
    }

    /** Da chiamare solo sul thread io. */
    private void closeAll() {
        if (poller != null) { poller.cancel(false); poller = null; }
        if (helix != null) { helix.close(); helix = null; }
        if (usbConn != null) {
            if (usbIface != null) usbConn.releaseInterface(usbIface);
            usbConn.close();
        }
        usbConn = null;
        usbIface = null;
        current = null;
    }

    private void setStatus(final String s) {
        runOnUiThread(() -> status.setText(s));
    }

    /** Ultime righe di traffico USB (esadecimale), utili se la HX Stomp non risponde come nella cattura. */
    private void addLog(final String line) {
        runOnUiThread(() -> {
            log.append(line.length() > 120 ? line.substring(0, 120) + "…" : line).append('\n');
            if (log.length() > 6000) log.delete(0, log.length() - 6000);
            logView.setText(log);
        });
    }
}
