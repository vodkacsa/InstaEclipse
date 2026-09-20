package ps.reso.instaeclipse.mods.profile;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** A child of the real scrolling profile header. Never attaches to the activity/window. */
public final class ProfileHeaderLabel {
    private static final String TAG = "ie_inline_profile_relationship";
    private View anchor;
    private TextView label;
    private TextView annotated;
    private final Object marker = new Object();
    private String lastText;

    public void render(View profileRoot, String text) {
        View header = find(profileRoot, v -> {
            String id = resourceName(v);
            return v instanceof ViewGroup && (id.equals("row_profile_header") || id.equals("profile_header")
                    || id.equals("profile_header_container") || id.equals("profile_header_info"));
        }, 300);
        if (header == null) {
            // Resolve a header from its named bio/Threads child without touching the posts grid.
            View detail = find(profileRoot, v -> resourceName(v).contains("profile_header")
                    && (resourceName(v).contains("bio") || isThreads(v)), 300);
            if (detail != null && detail.getParent() instanceof View) header = (View) detail.getParent();
        }
        if (header == null) { clear(); return; }
        renderHeader(header, text);
    }

    void renderHeader(View header, String text) {
        View target = find(header, ProfileHeaderLabel::isThreads, 160);
        boolean threads = target != null;
        if (target == null) target = find(header, v -> v instanceof TextView
                && (resourceName(v).contains("bio") || resourceName(v).equals("profile_header_full_name")), 160);
        if (target == null || TextUtils.isEmpty(text)) { clear(); return; }
        if (anchor == target && isWithin(target, header)) {
            if (label != null && label.getParent() != null && isWithin(label, header)) {
                if (!text.contentEquals(label.getText())) label.setText(text);
                return;
            }
            if (annotated != null && hasAnnotation(annotated) && text.equals(lastText)) return;
        }
        clear();
        anchor = target;
        lastText = text;
        TextView sample = target instanceof TextView ? (TextView) target
                : (TextView) find(target, v -> v instanceof TextView, 40);

        // Add a real sibling in a row whose parent knows how to measure and place it.
        if (threads && insertIntoLinearParent(target, header, sample, text, LinearLayout.HORIZONTAL)) return;
        if (insertIntoLinearParent(target, header, sample, text, LinearLayout.VERTICAL)) return;

        // Constraint/custom containers cannot safely accept arbitrary layout params. Extend
        // the existing native text instead, preserving its spans, style, click handler and layout.
        if (sample != null) {
            annotated = sample;
            SpannableStringBuilder value = new SpannableStringBuilder(sample.getText());
            int start = value.length();
            value.append(threads ? "  ·  " : "\n").append(text);
            value.setSpan(marker, start, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            value.setSpan(new RelativeSizeSpan(0.9f), start, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sample.setText(value);
        }
    }

    private boolean insertIntoLinearParent(View target, View header, TextView sample, String text, int orientation) {
        View child = target;
        for (int depth = 0; depth < 4 && child != header; depth++) {
            ViewParent parent = child.getParent();
            if (!(parent instanceof ViewGroup) || !isWithin((View) parent, header)) return false;
            if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == orientation) {
                LinearLayout row = (LinearLayout) parent;
                // Weighted action-button rows are not profile metadata rows.
                boolean weighted = false;
                for (int i = 0; i < row.getChildCount(); i++) {
                    ViewGroup.LayoutParams lp = row.getChildAt(i).getLayoutParams();
                    if (lp instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams) lp).weight > 0) weighted = true;
                }
                if (orientation == LinearLayout.HORIZONTAL && weighted) return false;
                TextView candidate = makeLabel(target, sample, text);
                if (orientation == LinearLayout.HORIZONTAL && row.getWidth() > 0) {
                    int used = row.getPaddingLeft() + row.getPaddingRight();
                    for (int i = 0; i < row.getChildCount(); i++) {
                        View existing = row.getChildAt(i);
                        if (existing.getVisibility() == View.GONE) continue;
                        used += existing.getMeasuredWidth();
                        ViewGroup.LayoutParams lp = existing.getLayoutParams();
                        if (lp instanceof ViewGroup.MarginLayoutParams) {
                            used += ((ViewGroup.MarginLayoutParams) lp).leftMargin + ((ViewGroup.MarginLayoutParams) lp).rightMargin;
                        }
                    }
                    int available = row.getWidth() - used - dp(target, 8);
                    if (available < candidate.getPaint().measureText(text)) return false;
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                if (orientation == LinearLayout.HORIZONTAL) {
                    params.setMarginStart(dp(target, 8));
                    params.gravity = android.view.Gravity.CENTER_VERTICAL;
                } else {
                    params.topMargin = dp(target, 4);
                    params.bottomMargin = dp(target, 2);
                }
                row.addView(candidate, row.indexOfChild(child) + 1, params);
                label = candidate;
                return true;
            }
            child = (View) parent;
        }
        return false;
    }

    private static TextView makeLabel(View anchor, TextView sample, String text) {
        TextView result = new TextView(anchor.getContext());
        result.setTag(TAG);
        result.setText(text);
        result.setTextSize(12);
        result.setMaxLines(1);
        result.setEllipsize(TextUtils.TruncateAt.END);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (sample != null) {
            result.setTypeface(sample.getTypeface());
            result.setTextColor(sample.getTextColors());
            result.setAlpha(0.72f);
        } else {
            android.content.res.TypedArray style = anchor.getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
            try { result.setTextColor(style.getColorStateList(0) != null ? style.getColorStateList(0) : result.getTextColors()); }
            finally { style.recycle(); }
        }
        return result;
    }

    public void clear() {
        if (label != null && label.getParent() instanceof ViewGroup) ((ViewGroup) label.getParent()).removeView(label);
        if (annotated != null && annotated.getText() instanceof Spanned) {
            Spanned current = (Spanned) annotated.getText();
            int start = current.getSpanStart(marker), end = current.getSpanEnd(marker);
            if (start >= 0 && end >= start) {
                SpannableStringBuilder restored = new SpannableStringBuilder(current);
                restored.removeSpan(marker);
                restored.delete(start, end);
                annotated.setText(restored);
            }
        }
        anchor = null; label = null; annotated = null; lastText = null;
    }

    private boolean hasAnnotation(TextView view) {
        return view.getText() instanceof Spanned && ((Spanned) view.getText()).getSpanStart(marker) >= 0;
    }
    private static boolean isThreads(View view) {
        if (TAG.equals(view.getTag()) || view.getVisibility() != View.VISIBLE) return false;
        String id = resourceName(view);
        if ((id.contains("threads") || id.contains("barcelona"))
                && (id.contains("badge") || id.contains("button") || id.contains("profile"))) return true;
        CharSequence description = view.getContentDescription();
        return description != null && description.toString().toLowerCase(Locale.ROOT).contains("threads");
    }
    private static String resourceName(View view) {
        try { return view.getResources().getResourceEntryName(view.getId()).toLowerCase(Locale.ROOT); }
        catch (RuntimeException ignored) { return ""; }
    }
    private static boolean isWithin(View child, View ancestor) {
        for (View current = child; current != null; ) {
            if (current == ancestor) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }
    private interface Match { boolean test(View view); }
    private static View find(View root, Match matcher, int budget) { return find(root, matcher, new int[]{budget}); }
    private static View find(View root, Match matcher, int[] left) {
        if (root == null || left[0]-- <= 0 || TAG.equals(root.getTag()) || root.getVisibility() != View.VISIBLE) return null;
        if (matcher.test(root)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), matcher, left);
            if (found != null) return found;
        }
        return null;
    }
    private static int dp(View view, int value) { return Math.round(view.getResources().getDisplayMetrics().density * value); }
}
