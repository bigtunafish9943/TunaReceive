package com.limelight;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.RecentGames;

import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The games on each paired PC, for a home screen such as TunaHome to show as a row of its own.
 * Read only, and only what TunaReceive already shows: names, box art and when each was last
 * played. Lists come from TunaReceive's own cache, so a PC's games appear once TunaReceive has
 * listed them at least once. Hidden games are left out.
 *
 * content://games.com.tunastream.receive/games, one row per game, most recently played first.
 * Open a game with ShortcutTrampoline and the extras named in TunaHome's PcGames.
 */
public class GamesProvider extends ContentProvider {
    public static final String[] COLUMNS = {
            "pc_uuid", "pc_name", "app_id", "app_name", "app_uuid", "hdr", "last_played", "poster",
    };

    private static final class Row {
        final Object[] values;
        final long played;
        final String name;

        Row(Object[] values, long played, String name) {
            this.values = values;
            this.played = played;
            this.name = name;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (getContext() == null) {
            return cursor;
        }
        List<ComputerDetails> computers;
        ComputerDatabaseManager db = new ComputerDatabaseManager(getContext());
        try {
            computers = db.getAllComputers();
        } catch (Exception e) {
            return cursor;
        } finally {
            db.close();
        }
        DiskAssetLoader posters = new DiskAssetLoader(getContext());
        SharedPreferences hiddenPrefs = getContext().getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, 0);
        List<Row> rows = new ArrayList<>();
        for (ComputerDetails pc : computers) {
            if (pc.uuid == null) {
                continue;
            }
            List<NvApp> apps;
            try (InputStream in = CacheHelper.openCacheFileForInput(getContext().getCacheDir(), "applist", pc.uuid)) {
                apps = NvHTTP.getAppListByReader(new StringReader(CacheHelper.readInputStreamToString(in)));
            } catch (Exception e) {
                continue;
            }
            Set<String> hidden = new HashSet<>(hiddenPrefs.getStringSet(pc.uuid, new HashSet<String>()));
            for (NvApp app : apps) {
                if (hidden.contains(String.valueOf(app.getAppId()))) {
                    continue;
                }
                long played = RecentGames.lastPlayed(getContext(), pc.uuid, app.getAppId());
                String poster = posters.getFile(pc.uuid, app.getAppId()).exists()
                        ? "content://" + PosterContentProvider.AUTHORITY + "/boxart/" + pc.uuid + "/" + app.getAppId()
                        : null;
                rows.add(new Row(new Object[]{
                        pc.uuid, pc.name, app.getAppId(), app.getAppName(), app.getAppUUID(),
                        app.isHdrSupported() ? 1 : 0, played, poster,
                }, played, app.getAppName() == null ? "" : app.getAppName()));
            }
        }
        // Most recently played first, then by name
        Collections.sort(rows, (a, b) -> {
            if (a.played != b.played) {
                return Long.compare(b.played, a.played);
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        for (Row row : rows) {
            cursor.addRow(row.values);
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.tunastream.game";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }
}
