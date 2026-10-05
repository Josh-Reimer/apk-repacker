package com.apkrepacker.apk;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Thread-safe, append-only diagnostic log for a single repackage run. The UI renders
 * this in a scrollable view; nothing here is uploaded anywhere.
 */
public final class BuildLog {

    public enum Level { INFO, WARN, ERROR }

    public static final class Entry {
        public final long timestamp;
        public final Level level;
        public final String message;

        Entry(Level level, String message) {
            this.timestamp = System.currentTimeMillis();
            this.level = level;
            this.message = message;
        }

        @Override
        public String toString() {
            String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(timestamp));
            String tag = level == Level.INFO ? "   " : level.name();
            return t + " " + tag + "  " + message;
        }
    }

    public interface Observer {
        void onEntry(Entry entry);
    }

    private final List<Entry> entries = new ArrayList<>();
    private volatile Observer observer;

    public void setObserver(Observer observer) {
        this.observer = observer;
    }

    public void info(String msg) { add(Level.INFO, msg); }
    public void warn(String msg) { add(Level.WARN, msg); }
    public void error(String msg) { add(Level.ERROR, msg); }

    private synchronized void add(Level level, String msg) {
        Entry e = new Entry(level, msg);
        entries.add(e);
        Observer o = observer;
        if (o != null) {
            o.onEntry(e);
        }
    }

    public synchronized List<Entry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized String asText() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e).append('\n');
        }
        return sb.toString();
    }
}
