package it.helixpresets;

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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
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
    private ArrayAdapter<Preset> adapter;
    private TextView status, logView;
    private Button button;
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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        status = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        button = findViewById(R.id.connect);
        ListView list = findViewById(R.id.presets);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, presets);
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { select(presets.get(pos)); }
        });
        button.setOnClickListener(v -> connect());

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
                    adapter.notifyDataSetChanged();
                });
                setStatus(result.size() + " preset letti. Tocca un preset per selezionarlo sulla HX Stomp.");
                poller = io.scheduleWithFixedDelay(this::pollEvents, 500, 300, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                setStatus("Errore: " + e.getMessage());
                closeAll();
            } finally {
                runOnUiThread(() -> button.setEnabled(true));
            }
        });
    }

    /** Tocco sulla lista: comando 20 con la posizione del preset nell'elenco. */
    private void select(final Preset p) {
        if (helix == null) { setStatus("Non collegato"); return; }
        setStatus("Seleziono " + p + "…");
        io.execute(() -> {
            try {
                if (helix == null) return;
                helix.select(p.position);
                setStatus("Selezionato: " + p);
            } catch (Exception e) {
                setStatus("Errore nella selezione: " + e.getMessage());
            }
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
