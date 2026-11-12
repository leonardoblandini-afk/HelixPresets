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
import android.view.MotionEvent;
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
import java.util.concurrent.atomic.AtomicInteger;

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
    private final StringBuilder fullLog = new StringBuilder(); // ultimi ~300 KB di traffico, per "Copia log"

    // Volume dell'amp acceso: valore 0..1 (display 0..10). Il blocco si legge dal preset, il parametro 5 è quello della cattura.
    // Valori in centesimi (93 = 9.3 sul display, 0.93 sul cavo). Il volume si cambia con i tasti − e +; se differisce dal valore letto
    // (volBaseline) il preset viene salvato da solo sulla HX Stomp dopo SAVE_DELAY_MS dall'ultimo tocco.
    private static final int SAVE_DELAY_MS = 1500;
    private Button volMinus, volPlus;
    private TextView volLabel, volValue, volState;
    private volatile int volTarget = -1, volBaseline = -1;
    private final AtomicInteger volPending = new AtomicInteger(-1);
    private ScheduledFuture<?> saveTask;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

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

    // Il ramo else gira solo su Android < 13, dove il flag RECEIVER_NOT_EXPORTED non esiste.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(0xFF0E1217);
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
        volLabel = findViewById(R.id.volLabel);
        volValue = findViewById(R.id.volValue);
        volState = findViewById(R.id.volState);
        volMinus = findViewById(R.id.volMinus);
        volPlus = findViewById(R.id.volPlus);
        setupStep(volMinus, -1);
        setupStep(volPlus, +1);
        findViewById(R.id.copyLog).setOnClickListener(v -> {
            String text;
            synchronized (fullLog) { text = fullLog.toString(); }
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Registro HX Stomp", text));
            status.setText("Registro copiato (" + text.length() / 1000 + " KB): incollalo in un messaggio");
        });
        button.setOnClickListener(v -> connect());
        findViewById(R.id.disconnect).setOnClickListener(v -> disconnect());
        findViewById(R.id.resetUsb).setOnClickListener(v -> io.execute(this::resetUsb));
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

    private void connectTo(final UsbDevice dev) {
        runOnUiThread(() -> { button.setEnabled(false); log.setLength(0); });
        setStatus("Lettura dei preset da " + dev.getProductName() + "…");
        io.execute(() -> {
            closeAll();
            try {
                usbConn = usb.openDevice(dev);
                if (usbConn == null) throw new Exception("openDevice ha restituito null");
                UsbEndpoint in = null, out = null;
                for (int k = 0; k < dev.getInterfaceCount(); k++) {
                    UsbInterface itf = dev.getInterface(k);
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
        adapter.setSelected(presets.indexOf(p)); // evidenziato subito: la HX Stomp conferma poco dopo
        io.execute(() -> {
            try {
                if (helix == null) return;
                flushSave(); // eventuali modifiche al volume non ancora salvate vanno salvate prima di cambiare preset o snapshot
                if (current != p) {
                    HelixConnection.PresetInfo info = helix.select(p.position);
                    p.snapshots = info.snapshots;
                    p.activeSnapshot = info.snapshot;
                    current = p;
                    final int index = presets.indexOf(p);
                    runOnUiThread(() -> { adapter.setSelected(index); showAmp(); });
                }
                if (snapshot == 0) setStatus("Selezionato: " + p);
                else {
                    if (p.activeSnapshot != snapshot) {
                        p.activeSnapshot = helix.selectSnapshot(snapshot);
                        runOnUiThread(() -> { adapter.notifyDataSetChanged(); showAmp(); });
                    }
                    setStatus("Snapshot " + snapshot + " di " + p);
                }
            } catch (Exception e) {
                addLog("ERRORE preset " + p + ": " + e);
                final int back = presets.indexOf(current);
                runOnUiThread(() -> adapter.setSelected(back));
                if (e.getMessage() != null && e.getMessage().startsWith("timeout")) {
                    setStatus("La HX Stomp non risponde: reset USB…");
                    resetUsb(); // il tentativo ripetuto è fallito: si riparte dalla connessione
                } else {
                    setStatus("Preset " + p + ": " + e.getMessage());
                }
            }
        });
    }

    private static String fmt(int hundredths) {
        return String.format(java.util.Locale.US, "%.1f", hundredths / 10.0);
    }

    /** Aggiorna testo e tasti con l'amp acceso (dopo la selezione di un preset o di uno snapshot). Da chiamare sul thread UI. */
    private void showAmp() {
        HelixConnection.Amp a = (helix == null || current == null) ? null : helix.amp;
        volState.setText("");
        if (helix == null || current == null) {
            volTarget = volBaseline = -1;
            volLabel.setText("AMP: seleziona un preset");
        } else if (a == null) {
            volTarget = volBaseline = -1;
            volLabel.setText("Nessun amp acceso in questo snapshot");
        } else {
            volTarget = volBaseline = Math.round(a.volume * 100);
            volLabel.setText("AMP attivo · blocco " + a.block + " · Volume");
        }
        volMinus.setEnabled(volTarget >= 0);
        volPlus.setEnabled(volTarget >= 0);
        volValue.setText(volTarget >= 0 ? fmt(volTarget) : "\u2013");
    }

    /** Tasto − o +: un tocco cambia di 0,1 (un centesimo sul cavo, come nella cattura); tenendo premuto si ripete. */
    @SuppressLint("ClickableViewAccessibility")
    private void setupStep(Button b, final int delta) {
        final Runnable repeat = new Runnable() {
            @Override public void run() { stepVolume(delta); ui.postDelayed(this, 120); }
        };
        b.setOnTouchListener((v, e) -> {
            if (!v.isEnabled()) return true;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    stepVolume(delta);
                    ui.postDelayed(repeat, 450);
                    break;
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    ui.removeCallbacks(repeat);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(repeat);
                    break;
                default:
                    break;
            }
            return true;
        });
    }

    private void stepVolume(int delta) {
        if (volTarget < 0) return;
        int v = Math.max(0, Math.min(100, volTarget + delta));
        if (v == volTarget) return;
        volTarget = v;
        volValue.setText(fmt(v));
        volState.setText(v == volBaseline ? "" : "modificato, salvo tra poco…");
        sendVolume(v);
        scheduleSave();
    }

    /** Tiene solo l'ultimo valore richiesto: se i tocchi vanno più veloci dell'USB, i valori intermedi si saltano. */
    private void sendVolume(int value) {
        if (volPending.getAndSet(value) != -1) return; // un invio è già in coda e prenderà il valore più recente
        io.execute(() -> {
            int v = volPending.getAndSet(-1);
            try {
                if (helix == null || v < 0) return;
                HelixConnection.Amp a = helix.amp;
                if (a == null) { setStatus("Nessun amp acceso in questo snapshot"); return; }
                helix.setParameter(a.block, HelixConnection.VOLUME_PARAM, v / 100f);
            } catch (Exception e) {
                setStatus("Volume: " + e.getMessage());
            }
        });
    }

    private synchronized void scheduleSave() {
        if (saveTask != null) saveTask.cancel(false);
        saveTask = io.schedule(this::saveIfChanged, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Sul thread io: salva subito se c'è una modifica in attesa. */
    private void flushSave() {
        synchronized (this) {
            if (saveTask != null) { saveTask.cancel(false); saveTask = null; }
        }
        saveIfChanged();
    }

    /** Sul thread io: se il volume richiesto è diverso dall'ultimo valore letto o salvato, salva il preset sulla HX Stomp. */
    private void saveIfChanged() {
        final int target = volTarget, base = volBaseline;
        if (helix == null || target < 0 || target == base) return;
        try {
            final boolean clean = helix.saveCurrentPreset();
            volBaseline = target;
            final String msg = clean ? "Salvato sulla patch \u2713" : "Salvato (il preset risulta ancora modificato)";
            runOnUiThread(() -> volState.setText(msg));
            setStatus("Volume " + fmt(target) + ": " + msg);
        } catch (Exception e) {
            addLog("ERRORE salvataggio: " + e);
            final String msg = "Salvataggio non riuscito: " + e.getMessage();
            runOnUiThread(() -> volState.setText(msg));
        }
    }

    private void disconnect() {
        // Con una modifica del volume non ancora salvata si aspetta il salvataggio; altrimenti si interrompe subito
        // qualunque attesa in corso, così il tasto agisce anche se la HX Stomp non risponde.
        final boolean pending = volTarget >= 0 && volTarget != volBaseline;
        final HelixConnection h = helix;
        if (!pending && h != null) h.abort();
        current = null;
        presets.clear();
        adapter.setSelected(-1);
        showAmp();
        status.setText("Scollego…");
        io.execute(() -> {
            try {
                if (pending) flushSave();
            } catch (RuntimeException e) {
                addLog("ERRORE: " + e);
            }
            try {
                closeAll();
            } catch (RuntimeException e) {
                addLog("ERRORE chiusura: " + e);
            }
            setStatus("Scollegato");
        });
    }

    /**
     * Reset della connessione USB (sul thread io): chiude le sessioni, chiede al sistema il reset della porta
     * (UsbDeviceConnection.resetDevice), aspetta che la HX Stomp si ripresenti sul bus e si ricollega rileggendo i preset.
     * Se Android ha tolto il permesso USB dopo il reset, compare di nuovo la richiesta.
     */
    private void resetUsb() {
        addLog("-- reset USB");
        runOnUiThread(() -> { presets.clear(); adapter.setSelected(-1); current = null; showAmp(); status.setText("Reset USB…"); });
        try {
            if (poller != null) { poller.cancel(false); poller = null; }
            if (helix != null) { helix.abort(); helix.close(); helix = null; }
            current = null;
            if (usbConn != null) {
                // resetDevice() non è nell'API pubblica di Android (è nascosto): lo si prova via reflection; se manca o è bloccato
                // resta comunque la chiusura e la riapertura della connessione, con un nuovo handshake.
                try {
                    Object ok = UsbDeviceConnection.class.getMethod("resetDevice").invoke(usbConn);
                    addLog("resetDevice: " + ok);
                } catch (Exception | LinkageError e) {
                    addLog("resetDevice non disponibile (" + e + "): solo chiusura e riapertura");
                }
                if (usbIface != null) usbConn.releaseInterface(usbIface);
                usbConn.close();
            }
        } catch (RuntimeException e) {
            addLog("ERRORE reset: " + e);
        }
        usbConn = null;
        usbIface = null;
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } // la HX Stomp si ripresenta sul bus
        runOnUiThread(this::connect);
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
        final String stamped = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date()) + " " + line;
        synchronized (fullLog) {
            fullLog.append(stamped).append('\n');
            if (fullLog.length() > 300000) fullLog.delete(0, fullLog.length() - 300000);
        }
        runOnUiThread(() -> {
            log.append(line.length() > 120 ? line.substring(0, 120) + "…" : line).append('\n');
            if (log.length() > 6000) log.delete(0, log.length() - 6000);
            logView.setText(log);
        });
    }
}
