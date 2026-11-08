package it.helixpresets;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.io.IOException;
import java.util.Arrays;

import it.helixpresets.protocol.Transport;

/** Endpoint bulk dell'interfaccia Line 6: una trama per transfer, come nella cattura (lettura con timeout di 100 ms). */
final class UsbTransport implements Transport {
    private final UsbDeviceConnection conn;
    private final UsbEndpoint in, out;
    private final byte[] buf = new byte[512];

    UsbTransport(UsbDeviceConnection conn, UsbEndpoint in, UsbEndpoint out) {
        this.conn = conn; this.in = in; this.out = out;
    }

    @Override public void write(byte[] frame) throws IOException {
        int n = conn.bulkTransfer(out, frame, frame.length, 1000);
        if (n != frame.length) throw new IOException("scrittura USB fallita (" + n + ")");
    }

    @Override public byte[] read(int timeoutMs) {
        int n = conn.bulkTransfer(in, buf, buf.length, timeoutMs);
        return n > 0 ? Arrays.copyOf(buf, n) : null;
    }
}
