package com.apkrepacker.apk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Discovery of installed applications via PackageManager, plus conflict checks. */
public final class InstalledApps {

    public static final class Entry {
        public final String label;
        public final String packageName;
        public final boolean system;

        Entry(String label, String packageName, boolean system) {
            this.label = label;
            this.packageName = packageName;
            this.system = system;
        }
    }

    private final Context context;

    public InstalledApps(Context context) {
        this.context = context.getApplicationContext();
    }

    /** All launchable/installed apps, sorted by label. Includes user apps first. */
    public List<Entry> list(boolean includeSystem) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> infos = pm.getInstalledApplications(0);
        List<Entry> out = new ArrayList<>();
        for (ApplicationInfo ai : infos) {
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && !includeSystem) {
                continue;
            }
            if (ai.sourceDir == null) {
                continue;
            }
            CharSequence label = pm.getApplicationLabel(ai);
            out.add(new Entry(label != null ? label.toString() : ai.packageName,
                    ai.packageName, system));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** True if an app with this exact package name is already installed. */
    public boolean isInstalled(String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
