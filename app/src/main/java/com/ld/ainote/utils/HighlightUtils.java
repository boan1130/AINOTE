package com.ld.ainote.utils;

import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.widget.EditText;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class HighlightUtils {

    public static final int HIGHLIGHT_YELLOW = 0x66FFF59D;

    public static CharSequence buildHighlighted(CharSequence raw) {
        if (raw == null) return "";
        String s = raw.toString();
        SpannableStringBuilder out = new SpannableStringBuilder();

        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf("[[", i);
            if (open < 0) { out.append(s.substring(i)); break; }
            out.append(s, i, open);
            int close = s.indexOf("]]", open + 2);
            if (close < 0) { out.append(s.substring(open)); break; }

            int start = out.length();
            out.append(s, open + 2, close);
            int end = out.length();
            out.setSpan(new BackgroundColorSpan(HIGHLIGHT_YELLOW), start, end, 0);
            i = close + 2;
        }
        return out;
    }

    public static List<String> extractHighlights(String raw) {
        List<String> res = new ArrayList<>();
        if (raw == null) return res;
        int i = 0;
        while (i < raw.length()) {
            int open = raw.indexOf("[[", i);
            if (open < 0) break;
            int close = raw.indexOf("]]", open + 2);
            if (close < 0) break;
            res.add(raw.substring(open + 2, close));
            i = close + 2;
        }
        return res;
    }

    public static void addMark(EditText et) {
        int s = Math.max(0, et.getSelectionStart());
        int e = Math.max(0, et.getSelectionEnd());
        if (s == e) return;
        if (s > e) { int t = s; s = e; e = t; }
        et.getText().insert(e, "]]");
        et.getText().insert(s, "[[");
    }

    public static void removeNearestMark(EditText et) {
        String text = et.getText().toString();
        int caret = Math.max(0, et.getSelectionStart());
        int open = text.lastIndexOf("[[", caret);
        int close = text.indexOf("]]", caret);
        if (open >= 0 && close > open) {
            et.getText().delete(close, Math.min(close + 2, et.length()));
            et.getText().delete(open, Math.min(open + 2, et.length()));
        }
    }

    public static void applyTo(TextView tv, CharSequence raw) {
        tv.setText(buildHighlighted(raw));
    }
}
