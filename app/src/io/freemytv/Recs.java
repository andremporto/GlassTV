package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;

import java.io.File;
import java.util.Calendar;

/**
 * Data structures and utilities for home screen cards and hero display.
 * Legacy movie/streaming API endpoints (TMDB, Watchmode) have been completely removed
 * in favor of the dynamic YouTube feed.
 */
final class Recs {
    private Recs() {}

    static final class Pick {
        int id;
        String kind;
        String title, backdrop, meta = "", app, pkg;
        String link;
        boolean detailed;
        File photo;

        // YouTube dynamic integration fields
        String videoId;
        String channelTitle;
        String duration;
        Bitmap thumbnail;
        BitmapShader thumbnailShader;
        boolean isPairingCard;
        String userCode;
        String verificationUrl;
    }

    static String key(Pick p) {
        if (p == null) return "";
        if (p.isPairingCard) return "pairing:" + p.userCode;
        if (p.videoId != null) return "yt:" + p.videoId + ":" + (p.thumbnail != null ? "1" : "0");
        if (p.photo != null) return "photo:" + p.photo.getName();
        return (p.kind != null ? p.kind : "") + ":" + p.id;
    }

    static String periodLabel() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? Ui.tr("THIS MORNING") : hour >= 12 && hour < 18 ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : p.app;
    }
}
