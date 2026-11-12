package it.helixpresets;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

import it.helixpresets.protocol.Preset;

/** Riga = nome del preset + tre bottoni, uno per snapshot (con il nome letto dalla HX Stomp). */
final class PresetAdapter extends BaseAdapter {
    interface Listener {
        void onPreset(Preset p);
        void onSnapshot(Preset p, int snapshot); // snapshot da 1 a 3
    }

    private final Context ctx;
    private final List<Preset> items;
    private final Listener listener;
    private volatile int selected = -1;

    PresetAdapter(Context ctx, List<Preset> items, Listener listener) {
        this.ctx = ctx; this.items = items; this.listener = listener;
    }

    void setSelected(int position) { selected = position; notifyDataSetChanged(); }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    // Un colore per snapshot: blu, arancio, magenta.
    private static final int[] COLORS = { 0xFF29B6F6, 0xFFFFA726, 0xFFEC407A };

    private float dp(float v) { return v * ctx.getResources().getDisplayMetrics().density; }

    private GradientDrawable shape(int fill, int stroke, int strokeWidthDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(14));
        g.setStroke((int) dp(strokeWidthDp), stroke);
        return g;
    }

    @Override public View getView(int i, View v, ViewGroup parent) {
        if (v == null) v = LayoutInflater.from(ctx).inflate(R.layout.row_preset, parent, false);
        final Preset p = items.get(i);
        ((TextView) v.findViewById(R.id.number)).setText(String.format("%03d", p.position));
        ((TextView) v.findViewById(R.id.name)).setText(p.name.isEmpty() ? "(vuoto)" : p.name);
        v.findViewById(R.id.card).setBackgroundResource(i == selected ? R.drawable.bg_card_sel : R.drawable.bg_card);
        v.setOnClickListener(x -> listener.onPreset(p));
        int[] ids = { R.id.snap1, R.id.snap2, R.id.snap3 };
        for (int k = 0; k < 3; k++) {
            final int n = k + 1;
            Button b = v.findViewById(ids[k]);
            String[] names = p.snapshots;
            String name = names != null && names[k] != null && !names[k].isEmpty() ? names[k] : "SNAPSHOT " + n;
            boolean active = p.activeSnapshot == n;
            int color = COLORS[k];
            // Attivo: pieno col colore dello snapshot, testo scuro, bordo bianco. Inattivo: scuro con bordo colorato.
            b.setBackground(active ? shape(color, 0xFFFFFFFF, 3) : shape(0xFF232C38, color, 2));
            b.setTextColor(active ? 0xFF10151B : 0xFFFFFFFF);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            String top = (active ? "\u25CF " : "") + "SNAP " + n;
            sb.append(top).append('\n').append(name);
            sb.setSpan(new RelativeSizeSpan(0.72f), 0, top.length(), 0);
            sb.setSpan(new StyleSpan(Typeface.BOLD), top.length() + 1, sb.length(), 0);
            b.setText(sb);
            b.setTextSize(17);
            b.setOnClickListener(x -> listener.onSnapshot(p, n));
        }
        return v;
    }
}
