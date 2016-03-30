/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.util.Preconditions;

import libcore.util.Objects;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * User information used by {@link ShortcutService}.
 */
class ShortcutUser {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "user";
    private static final String TAG_LAUNCHER = "launcher";

    private static final String ATTR_VALUE = "value";

    static final class PackageWithUser {
        final int userId;
        final String packageName;

        private PackageWithUser(int userId, String packageName) {
            this.userId = userId;
            this.packageName = Preconditions.checkNotNull(packageName);
        }

        public static PackageWithUser of(int launcherUserId, String packageName) {
            return new PackageWithUser(launcherUserId, packageName);
        }

        public static PackageWithUser of(ShortcutPackageItem spi) {
            return new PackageWithUser(spi.getPackageUserId(), spi.getPackageName());
        }

        @Override
        public int hashCode() {
            return packageName.hashCode() ^ userId;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof PackageWithUser)) {
                return false;
            }
            final PackageWithUser that = (PackageWithUser) obj;

            return userId == that.userId && packageName.equals(that.packageName);
        }

        @Override
        public String toString() {
            return String.format("{Launcher: %d, %s}", userId, packageName);
        }
    }

    @UserIdInt
    final int mUserId;

    private final ArrayMap<String, ShortcutPackage> mPackages = new ArrayMap<>();

    private final ArrayMap<PackageWithUser, ShortcutLauncher> mLaunchers = new ArrayMap<>();

    private ComponentName mLauncherComponent;

    public ShortcutUser(int userId) {
        mUserId = userId;
    }

    public ArrayMap<String, ShortcutPackage> getPackages() {
        return mPackages;
    }

    public ArrayMap<PackageWithUser, ShortcutLauncher> getAllLaunchers() {
        return mLaunchers;
    }

    public void addLauncher(ShortcutLauncher launcher) {
        mLaunchers.put(PackageWithUser.of(launcher.getPackageUserId(),
                launcher.getPackageName()), launcher);
    }

    public ShortcutLauncher removeLauncher(
            @UserIdInt int packageUserId, @NonNull String packageName) {
        return mLaunchers.remove(PackageWithUser.of(packageUserId, packageName));
    }

    public ShortcutPackage getPackageShortcuts(@NonNull String packageName) {
        ShortcutPackage ret = mPackages.get(packageName);
        if (ret == null) {
            ret = new ShortcutPackage(mUserId, packageName);
            mPackages.put(packageName, ret);
        }
        return ret;
    }

    public ShortcutLauncher getLauncherShortcuts(@NonNull String packageName,
            @UserIdInt int launcherUserId) {
        final PackageWithUser key = PackageWithUser.of(launcherUserId, packageName);
        ShortcutLauncher ret = mLaunchers.get(key);
        if (ret == null) {
            ret = new ShortcutLauncher(mUserId, packageName, launcherUserId);
            mLaunchers.put(key, ret);
        }
        return ret;
    }

    public void forAllPackageItems(Consumer<ShortcutPackageItem> callback) {
        {
            final int size = mLaunchers.size();
            for (int i = 0; i < size; i++) {
                callback.accept(mLaunchers.valueAt(i));
            }
        }
        {
            final int size = mPackages.size();
            for (int i = 0; i < size; i++) {
                callback.accept(mPackages.valueAt(i));
            }
        }
    }

    public void unshadowPackage(ShortcutService s, @NonNull String packageName,
            @UserIdInt int packageUserId) {
        forPackageItem(packageName, packageUserId, spi -> {
            Slog.i(TAG, String.format("Restoring for %s, user=%d", packageName, packageUserId));
            spi.ensureNotShadowAndSave(s);
        });
    }

    public void forPackageItem(@NonNull String packageName, @UserIdInt int packageUserId,
            Consumer<ShortcutPackageItem> callback) {
        forAllPackageItems(spi -> {
            if ((spi.getPackageUserId() == packageUserId)
                    && spi.getPackageName().equals(packageName)) {
                callback.accept(spi);
            }
        });
    }

    public void saveToXml(ShortcutService s, XmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException {
        out.startTag(null, TAG_ROOT);

        ShortcutService.writeTagValue(out, TAG_LAUNCHER,
                mLauncherComponent);

        // Can't use forEachPackageItem due to the checked exceptions.
        {
            final int size = mLaunchers.size();
            for (int i = 0; i < size; i++) {
                saveShortcutPackageItem(s, out, mLaunchers.valueAt(i), forBackup);
            }
        }
        {
            final int size = mPackages.size();
            for (int i = 0; i < size; i++) {
                saveShortcutPackageItem(s, out, mPackages.valueAt(i), forBackup);
            }
        }

        out.endTag(null, TAG_ROOT);
    }

    private void saveShortcutPackageItem(ShortcutService s, XmlSerializer out,
            ShortcutPackageItem spi, boolean forBackup) throws IOException, XmlPullParserException {
        if (forBackup) {
            if (!s.shouldBackupApp(spi.getPackageName(), spi.getPackageUserId())) {
                return; // Don't save.
            }
            if (spi.getPackageUserId() != spi.getOwnerUserId()) {
                return; // Don't save cross-user information.
            }
        }
        spi.saveToXml(out, forBackup);
    }

    public static ShortcutUser loadFromXml(ShortcutService s, XmlPullParser parser, int userId,
            boolean fromBackup) throws IOException, XmlPullParserException {
        final ShortcutUser ret = new ShortcutUser(userId);

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();

            if (depth == outerDepth + 1) {
                switch (tag) {
                    case TAG_LAUNCHER: {
                        ret.mLauncherComponent = ShortcutService.parseComponentNameAttribute(
                                parser, ATTR_VALUE);
                        continue;
                    }
                    case ShortcutPackage.TAG_ROOT: {
                        final ShortcutPackage shortcuts = ShortcutPackage.loadFromXml(
                                s, parser, userId, fromBackup);

                        // Don't use addShortcut(), we don't need to save the icon.
                        ret.getPackages().put(shortcuts.getPackageName(), shortcuts);
                        continue;
                    }

                    case ShortcutLauncher.TAG_ROOT: {
                        ret.addLauncher(ShortcutLauncher.loadFromXml(parser, userId, fromBackup));
                        continue;
                    }
                }
            }
            ShortcutService.warnForInvalidTag(depth, tag);
        }
        return ret;
    }

    public ComponentName getLauncherComponent() {
        return mLauncherComponent;
    }

    public void setLauncherComponent(ShortcutService s, ComponentName launcherComponent) {
        if (Objects.equal(mLauncherComponent, launcherComponent)) {
            return;
        }
        mLauncherComponent = launcherComponent;
        s.scheduleSaveUser(mUserId);
    }

    public void resetThrottling() {
        for (int i = mPackages.size() - 1; i >= 0; i--) {
            mPackages.valueAt(i).resetThrottling();
        }
    }

    public void dump(@NonNull ShortcutService s, @NonNull PrintWriter pw, @NonNull String prefix) {
        pw.print(prefix);
        pw.print("User: ");
        pw.print(mUserId);
        pw.println();

        pw.print(prefix);
        pw.print("  ");
        pw.print("Default launcher: ");
        pw.print(mLauncherComponent);
        pw.println();

        for (int i = 0; i < mLaunchers.size(); i++) {
            mLaunchers.valueAt(i).dump(s, pw, prefix + "  ");
        }

        for (int i = 0; i < mPackages.size(); i++) {
            mPackages.valueAt(i).dump(s, pw, prefix + "  ");
        }
    }
}
