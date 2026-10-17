package ps.reso.instaeclipse.mods.misc;

import android.app.AndroidAppHelper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ViewGroup;
import android.view.Gravity;
import android.text.TextUtils;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.net.Uri;
import android.content.Intent;
import android.content.ContextWrapper;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class StoryMentionHook {

    // Story @mentions live behind a two-step pipeline, not a single Media-level getter:
    //   Media -> LiveTreeMediaDict (found by type) -> "reel_mentions" field (raw tree entries)
    //   -> a converter method that turns each raw entry into an Interactive sticker object,
    //      with the actual mentioned User stored in one of Interactive's own fields.
    // Anchoring on the "reel_mentions" JSON key and the converter's own debug string is far
    // more stable across IG versions than matching a Media-level method's signature — those
    // getters get refactored/renamed/re-typed constantly (see git history of this file), but
    // literal JSON field names and hardcoded log/QPL strings don't change on a version bump.
    private static volatile Method rawMentionsGetter;   // LiveTreeMediaDict -> List (raw entries)
    private static volatile Method mentionsConverter;   // List(raw) -> List<Interactive w/ User field>

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String INLINE_BUTTON_TAG = "instaeclipse_story_mentions_inline";
    private static volatile long inlineGeneration = 0L;

    // ── Entry point ──────────────────────────────────────────────────────────

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        resolveMentionPipeline(bridge, classLoader);
        installButtonHook(bridge, classLoader);
        installClickHook(bridge, classLoader);
        installInlineButtonHook(bridge, classLoader);
        FeatureStatusTracker.setHooked("StoryMentions");
    }

    // ── DexKit: resolve the two-step mention pipeline ────────────────────────

    private static void resolveMentionPipeline(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method g = DexKitCache.loadMethod("MentionsRawGetter", classLoader);
            Method c = DexKitCache.loadMethod("MentionsConverter", classLoader);
            if (g != null && c != null) {
                rawMentionsGetter = g;
                mentionsConverter = c;
                ModuleLog.line("(IE|Mention) ✅ pipeline resolved from cache");
                return;
            }
        }

        try {
            // The paramCount-0 List getter guarded by the "reel_mentions" JSON key used to live
            // on LiveTreeMediaDict (<=436), but IG 442+ folded the live-tree model into
            // com.instagram.feed.media.Media, so the same getter now sits directly on Media.
            // Try the current home first, then the legacy class. Only stable
            // com.instagram.feed.media.* class names + the JSON key are used — never an X.* name.
            String[] mediaClasses = {
                    "com.instagram.feed.media.Media",             // 442+ (447: Media reel_mentions getter)
                    "com.instagram.feed.media.LiveTreeMediaDict"  // 436 and earlier
            };
            outer:
            for (String cls : mediaClasses) {
                List<MethodData> getters;
                try {
                    getters = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(cls)
                                    .paramCount(0)
                                    .usingEqStrings(List.of("reel_mentions"))));
                } catch (Throwable ignored) {
                    continue; // class absent on this build
                }
                for (MethodData md : getters) {
                    if (md.getName().equals("<clinit>")) continue;
                    try {
                        Method m = md.getMethodInstance(classLoader);
                        if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                        m.setAccessible(true);
                        rawMentionsGetter = m;
                        DexKitCache.saveMethod("MentionsRawGetter", m);
                        break outer;
                    } catch (Throwable ignored) {}
                }
            }
            if (rawMentionsGetter == null) ModuleLog.line("(IE|Mention) ❌ rawMentionsGetter not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) ❌ rawMentionsGetter query failed: " + t);
        }

        try {
            List<MethodData> converters = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramCount(1)
                            .usingEqStrings(List.of("MentionTappableObject.user is null; dropping mention sticker"))));
            for (MethodData md : converters) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                    m.setAccessible(true);
                    mentionsConverter = m;
                    DexKitCache.saveMethod("MentionsConverter", m);
                    break;
                } catch (Throwable ignored) {}
            }
            if (mentionsConverter == null) ModuleLog.line("(IE|Mention) ❌ mentionsConverter not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) ❌ mentionsConverter query failed: " + t);
        }

        if (rawMentionsGetter != null && mentionsConverter != null) {
            ModuleLog.line("(IE|Mention) ✅ pipeline resolved: " + rawMentionsGetter.getName() + " -> " + mentionsConverter.getName());
        }
    }

    // Walks an object's declared fields (and superclasses) for the first one whose exact
    // declared type matches typeName. Used to find LiveTreeMediaDict on Media, and the
    // mentioned User inside an Interactive sticker, without depending on obfuscated field names.
    private static Object findFieldByType(Object obj, String typeName) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().getName().equals(typeName)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null) return v;
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ── Hook 1: append "View Mentions" to the story options list ─────────────
    //
    // Same anchor as StoryDownloadHook — CharSequence[] builder with "[INTERNAL] Pause Playback".
    // Xposed stacks hooks, so both run independently on the same method.

    private void installButtonHook(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> builders = null;

        // Key renamed from "MentionButton": it cached a single builder; IG builds the menu with
        // a different CharSequence[] method per story owner (own vs others'), so hook them all.
        if (DexKitCache.isCacheValid()) {
            builders = DexKitCache.loadMethods("MentionButtons", classLoader);
        }

        if (builders == null || builders.isEmpty()) {
            try {
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingStrings("[INTERNAL] Pause Playback")));
                builders = new java.util.ArrayList<>(
                        ps.reso.instaeclipse.mods.media.StoryDownloadHook.storyOptionBuilders(methods, classLoader));
            } catch (Throwable t) {
                ModuleLog.line("(IE|Mention) ❌ button hook DexKit: " + t);
            }
        }

        if (builders == null || builders.isEmpty()) {
            ModuleLog.line("(IE|Mention) ❌ button builder not found");
            return;
        }
        DexKitCache.saveMethods("MentionButtons", builders);

        XC_MethodHook mentionHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.enableStoryMentions) return;
                    CharSequence[] original = (CharSequence[]) param.getResult();
                    if (original == null) return;
                    String mentionLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_btn_view_mentions);
                    for (CharSequence cs : original) {
                        if (mentionLabel.contentEquals(cs)) return;
                    }
                    CharSequence[] extended = new CharSequence[original.length + 1];
                    System.arraycopy(original, 0, extended, 0, original.length);
                    extended[original.length] = mentionLabel;
                    param.setResult(extended);
                }
            };
        int hooked = 0;
        for (Method m : builders) {
            try { XposedBridge.hookMethod(m, mentionHook); hooked++; }
            catch (Throwable t) { ModuleLog.line("(IE|Mention) ❌ button hook: " + t); }
        }
        ModuleLog.line("(IE|Mention) ✅ button hook installed on " + hooked + " builder(s)");
    }

    // ── Hook 2: handle "View Mentions" tap ───────────────────────────────────
    //
    // Same anchor as StoryDownloadHook click handler. We intercept only our label;
    // all other taps pass through to Instagram and to the StoryDownloadHook.

    private void installClickHook(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> dispatchers = ps.reso.instaeclipse.mods.media.StoryDownloadHook.storyClickDispatchers(bridge, classLoader);
        if (dispatchers.isEmpty()) {
            ModuleLog.line("(IE|Mention) ❌ click handler not found");
            return;
        }
        try {
            XC_MethodHook clickHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    try {
                        if (!FeatureFlags.enableStoryMentions) return;

                        CharSequence tapped = null;
                        for (Object a : param.args) {
                            if (a instanceof CharSequence cs && tapped == null) tapped = cs;
                        }
                        String mentionLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_btn_view_mentions);
                        if (tapped == null || !mentionLabel.contentEquals(tapped)) return;

                        param.setResult(null); // consume event

                        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                        Object media = null;
                        Context ctx = null;

                        if (param.thisObject != null) {
                            media = findMediaInGraph(param.thisObject, 0, visited);
                            ctx = findContext(param.thisObject);
                        }
                        for (Object a : param.args) {
                            if (a == null) continue;
                            if (media == null) media = findMediaInGraph(a, 0, visited);
                            if (ctx == null) ctx = findContext(a);
                        }

                        if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                        if (ctx == null) { ModuleLog.line("(IE|Mention) ❌ context not found"); return; }
                        if (media == null) { ModuleLog.line("(IE|Mention) ❌ Media not found"); return; }

                        showMentionsDialog(ctx, resolveMentions(media));
                    } catch (Throwable t) {
                        ModuleLog.line("(IE|Mention) ❌ click handler: " + t);
                    }
                }
            };
            for (Method m : dispatchers) XposedBridge.hookMethod(m, clickHook);
            ModuleLog.line("(IE|Mention) ✅ click hook installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) ❌ click hook: " + t);
        }
    }

    // ── Hook 3: compact "View mentions(n)" button beside the story header ─────
    //
    // The story viewer's current-item bind is already a proven stable anchor elsewhere in
    // InstaEclipse. It gives us the active ReelItem on every story change, so the button never
    // needs a floating app-wide overlay and always follows the currently displayed story.

    private void installInlineButtonHook(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("ReelViewerFragment.onCurrentActiveItemBound")));

            if (methods.isEmpty()) {
                ModuleLog.line("(IE|Mention) ❌ inline story bind not found");
                return;
            }

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final long generation = ++inlineGeneration;

                    Object reelItem = null;
                    for (Object arg : param.args) {
                        if (arg != null && arg.getClass().getName()
                                .equals("com.instagram.model.reels.ReelItem")) {
                            reelItem = arg;
                            break;
                        }
                    }

                    Context ctx = findContext(param.thisObject);
                    if (ctx == null) {
                        for (Object arg : param.args) {
                            ctx = findContext(arg);
                            if (ctx != null) break;
                        }
                    }

                    final Context finalCtx = ctx;
                    if (!FeatureFlags.enableStoryMentions || reelItem == null) {
                        mainHandler.post(() -> removeInlineButtonFromContext(finalCtx));
                        return;
                    }

                    Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                    Object media = findMediaInGraph(reelItem, 0, visited);
                    if (media == null) {
                        mainHandler.post(() -> removeInlineButtonFromContext(finalCtx));
                        return;
                    }

                    List<String> mentions = resolveMentions(media);
                    String storyUsername = resolveStoryUsername(reelItem, media);
                    List<String> snapshot = new ArrayList<>(mentions);

                    mainHandler.post(() ->
                            refreshInlineButton(finalCtx, storyUsername, snapshot, generation, 0));
                }
            };

            int hooked = 0;
            for (MethodData md : methods) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), hook);
                    hooked++;
                } catch (Throwable ignored) {}
            }

            ModuleLog.line("(IE|Mention) ✅ inline button hook installed on " + hooked + " bind method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) ❌ inline button hook: " + t);
        }
    }

    private static void refreshInlineButton(
            Context ctx,
            String storyUsername,
            List<String> mentions,
            long generation,
            int attempt
    ) {
        if (generation != inlineGeneration) return;

        Activity activity = findActivity(ctx);
        if (activity == null || activity.getWindow() == null) {
            if (attempt < 4) {
                mainHandler.postDelayed(() ->
                        refreshInlineButton(ctx, storyUsername, mentions, generation, attempt + 1), 80L);
            }
            return;
        }

        View root = activity.getWindow().getDecorView();
        removeInlineButton(root);

        if (!FeatureFlags.enableStoryMentions || mentions == null || mentions.isEmpty()) return;

        float dp = activity.getResources().getDisplayMetrics().density;
        TextView usernameView = findStoryUsernameView(root, storyUsername, dp);
        if (usernameView == null) {
            if (attempt < 4) {
                mainHandler.postDelayed(() ->
                        refreshInlineButton(ctx, storyUsername, mentions, generation, attempt + 1), 80L);
            }
            return;
        }

        View cluster = findHeaderTextCluster(usernameView, root, dp);
        ViewGroup host = findHeaderHost(cluster, root, dp);
        if (host == null) return;

        TextView button = new TextView(activity);
        button.setTag(INLINE_BUTTON_TAG);
        int mentionLabelRes = mentions.size() == 1
                ? R.string.ig_btn_view_mention
                : R.string.ig_btn_view_mentions;
        button.setText("@ " + I18n.t(activity, mentionLabelRes)
                + "(" + mentions.size() + ") ›");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTypeface(null, Typeface.BOLD);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMaxWidth((int) (160 * dp));
        button.setGravity(Gravity.CENTER);
        button.setPadding((int) (10 * dp), (int) (5 * dp),
                (int) (10 * dp), (int) (5 * dp));
        button.setBackground(roundRect(Color.parseColor("#66000000"), 18, activity, dp));
        button.setElevation(4 * dp);
        button.setContentDescription(I18n.t(activity, R.string.ig_btn_view_mentions)
                + " (" + mentions.size() + ")");
        button.setOnClickListener(v -> showMentionsDialog(activity, mentions));

        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        host.addView(button, lp);
        button.bringToFront();

        button.post(() -> positionInlineButton(button, host, cluster, dp));
    }

    private static void positionInlineButton(TextView button, ViewGroup host, View cluster, float dp) {
        if (button.getParent() != host || !cluster.isShown()) return;

        int[] hostLoc = new int[2];
        int[] clusterLoc = new int[2];
        host.getLocationOnScreen(hostLoc);
        cluster.getLocationOnScreen(clusterLoc);

        float x = clusterLoc[0] - hostLoc[0] + cluster.getWidth() + (8 * dp);
        float y = clusterLoc[1] - hostLoc[1]
                + ((cluster.getHeight() - button.getHeight()) / 2f);

        // Keep clear of Instagram's overflow control at the far right.
        float maxX = host.getWidth() - button.getWidth() - (48 * dp);
        if (maxX >= 0) x = Math.min(x, maxX);

        button.setX(Math.max(0, x));
        button.setY(Math.max(0, y));
    }

    private static TextView findStoryUsernameView(View root, String username, float dp) {
        List<TextView> candidates = new ArrayList<>();
        collectVisibleTextViews(root, candidates);

        TextView fallback = null;
        int fallbackY = Integer.MAX_VALUE;

        for (TextView tv : candidates) {
            CharSequence raw = tv.getText();
            if (raw == null) continue;
            String text = raw.toString().trim();
            if (text.isEmpty()) continue;

            int[] loc = new int[2];
            tv.getLocationOnScreen(loc);

            // Story header lives at the upper-left; ignore the story body/reply composer.
            if (loc[1] < 20 * dp || loc[1] > 190 * dp) continue;
            if (loc[0] > root.getWidth() * 0.65f) continue;

            if (username != null && !username.isEmpty() && username.equals(text)) {
                return tv;
            }

            if (!looksLikeUsername(text) || text.matches("\\d+[smhdw]")) continue;
            if (tv.getTypeface() == null || !tv.getTypeface().isBold()) continue;

            if (loc[1] < fallbackY) {
                fallback = tv;
                fallbackY = loc[1];
            }
        }
        return fallback;
    }

    private static void collectVisibleTextViews(View view, List<TextView> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView tv && tv.isShown()) out.add(tv);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectVisibleTextViews(group.getChildAt(i), out);
            }
        }
    }

    private static View findHeaderTextCluster(TextView usernameView, View root, float dp) {
        View best = usernameView;
        View current = usernameView;

        for (int level = 0; level < 5; level++) {
            if (!(current.getParent() instanceof ViewGroup parent)) break;

            int width = parent.getWidth();
            int height = parent.getHeight();
            if (width > 0 && height > 0
                    && width <= root.getWidth() * 0.68f
                    && height <= 78 * dp
                    && countTextViews(parent, 0) >= 2) {
                best = parent;
            }
            current = parent;
        }
        return best;
    }

    private static int countTextViews(View view, int depth) {
        if (view == null || depth > 4) return 0;
        int count = view instanceof TextView ? 1 : 0;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount() && count < 4; i++) {
                count += countTextViews(group.getChildAt(i), depth + 1);
            }
        }
        return count;
    }

    private static ViewGroup findHeaderHost(View cluster, View root, float dp) {
        View current = cluster;
        ViewGroup fallback = null;

        for (int level = 0; level < 6; level++) {
            if (!(current.getParent() instanceof ViewGroup parent)) break;

            if (parent.getWidth() > 0
                    && parent.getHeight() > 0
                    && parent.getHeight() <= 140 * dp) {
                fallback = parent;
            }

            if (parent.getWidth() >= root.getWidth() * 0.72f
                    && parent.getHeight() > 0
                    && parent.getHeight() <= 110 * dp) {
                return parent;
            }
            current = parent;
        }
        return fallback;
    }

    private static void removeInlineButtonFromContext(Context ctx) {
        Activity activity = findActivity(ctx);
        if (activity == null || activity.getWindow() == null) return;
        removeInlineButton(activity.getWindow().getDecorView());
    }

    private static void removeInlineButton(View view) {
        if (!(view instanceof ViewGroup group)) return;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (INLINE_BUTTON_TAG.equals(child.getTag())) {
                group.removeViewAt(i);
                continue;
            }
            removeInlineButton(child);
        }
    }

    private static Activity findActivity(Context ctx) {
        Context current = ctx;
        while (current != null) {
            if (current instanceof Activity activity) return activity;
            if (current instanceof ContextWrapper wrapper) {
                Context base = wrapper.getBaseContext();
                if (base == current) break;
                current = base;
                continue;
            }
            break;
        }
        return null;
    }

    private static String resolveStoryUsername(Object reelItem, Object media) {
        try {
            for (Method method : reelItem.getClass().getDeclaredMethods()) {
                if (method.getParameterCount() != 0) continue;
                if (!method.getReturnType().getName().equals("com.instagram.user.model.User")) continue;
                try {
                    method.setAccessible(true);
                    Object user = method.invoke(reelItem);
                    String username = UserUtils.callUsernameGetter(user);
                    if (username != null && !username.isEmpty()) return username;
                } catch (Throwable ignored) {}
            }

            Object directUser = findFieldByType(reelItem, "com.instagram.user.model.User");
            if (directUser != null) {
                String username = UserUtils.callUsernameGetter(directUser);
                if (username != null && !username.isEmpty()) return username;
            }

            Object mediaUser = findFieldByType(media, "com.instagram.user.model.User");
            if (mediaUser != null) {
                String username = UserUtils.callUsernameGetter(mediaUser);
                if (username != null && !username.isEmpty()) return username;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean looksLikeUsername(String s) {
        return s != null && s.length() >= 2 && s.length() <= 30
                && s.matches("[a-zA-Z0-9._]+")
                && !s.matches("\\d+");
    }

    // ── Mention extraction ────────────────────────────────────────────────────

    // media is already resolved by the caller — passed in directly
    private static List<String> resolveMentions(Object media) {
        List<String> usernames = new ArrayList<>();
        try {
            if (rawMentionsGetter == null || mentionsConverter == null) {
                ModuleLog.line("(IE|Mention) ❌ mention pipeline not resolved");
                return usernames;
            }

            // The getter may be declared directly on Media (442+) or on a sub-dict field of
            // Media (<=436). Derive the receiver from the resolved getter's own declaring class
            // instead of hardcoding a class name.
            Class<?> owner = rawMentionsGetter.getDeclaringClass();
            Object receiver = owner.isInstance(media) ? media : findFieldByType(media, owner.getName());
            if (receiver == null) {
                ModuleLog.line("(IE|Mention) ❌ mention receiver (" + owner.getName() + ") not found on media");
                return usernames;
            }

            Object rawResult = rawMentionsGetter.invoke(receiver);
            if (!(rawResult instanceof List<?> raw) || raw.isEmpty()) return usernames;

            Object convertedResult = mentionsConverter.invoke(null, raw);
            if (!(convertedResult instanceof List<?> list)) return usernames;

            for (Object item : list) {
                if (item == null) continue;
                // The converter returns Interactive stickers, not raw Users — the mentioned
                // User lives in one of Interactive's own fields (unless a future version
                // returns Users directly, which this also handles).
                Object user = item.getClass().getName().equals("com.instagram.user.model.User")
                        ? item : findFieldByType(item, "com.instagram.user.model.User");
                if (user == null) continue;
                String username = UserUtils.callUsernameGetter(user);
                if (username != null && !username.isEmpty()) usernames.add(username);
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) resolveMentions exception: " + t);
        }
        return usernames;
    }

    // Recursively walk fields (including Object-typed ones) to find a Media instance.
    // Checks runtime class name, not declared field type, so it works through Object fields.
    private static final int GRAPH_MAX_DEPTH = 6;

    static Object findMediaInGraph(Object obj, int depth, Set<Object> visited) {
        if (obj == null || depth > GRAPH_MAX_DEPTH) return null;
        if (!visited.add(obj)) return null;

        String className = obj.getClass().getName();
        if (!className.startsWith("com.instagram.") &&
                !className.startsWith("com.facebook.") &&
                !className.startsWith("X.")) return null;

        if (className.equals("com.instagram.feed.media.Media")) return obj;

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray()) continue;
                f.setAccessible(true);
                Object val;
                try { val = f.get(obj); } catch (Throwable ignored) { continue; }
                if (val == null) continue;

                String vn = val.getClass().getName();
                if (vn.equals("com.instagram.feed.media.Media")) return val;
                if (vn.startsWith("com.instagram.") || vn.startsWith("com.facebook.") || vn.startsWith("X.")) {
                    Object found = findMediaInGraph(val, depth + 1, visited);
                    if (found != null) return found;
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ── Mentions dialog ───────────────────────────────────────────────────────

    private static void showMentionsDialog(Context ctx, List<String> usernames) {
        mainHandler.post(() -> {
            try {
                IeDialog dlg = new IeDialog(ctx)
                        .title(I18n.t(ctx, R.string.ig_mention_dialog_title))
                        .message(usernames.isEmpty()
                                ? I18n.t(ctx, R.string.ig_mention_no_mentions)
                                : usernames.size() == 1
                                        ? I18n.t(ctx, R.string.ig_mention_subtitle_single)
                                        : I18n.t(ctx, R.string.ig_mention_subtitle, usernames.size()));

                // One row per mention; tapping copies that username and keeps the list open.
                for (String username : usernames) {
                    TextView row = new TextView(ctx);
                    row.setText("@" + username);
                    row.setTextColor(dlg.text);
                    row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    row.setTypeface(null, Typeface.BOLD);
                    row.setPadding(dlg.dp(16), dlg.dp(13), dlg.dp(14), dlg.dp(13));
                    row.setBackground(dlg.cardBg());
                    row.setOnClickListener(v -> {
                        dlg.dismiss();
                        openProfile(ctx, username);
                    });
                    dlg.view(row);
                }

                // Copy all button (only shown when more than one mention)
                if (usernames.size() > 1) {
                    dlg.primary(I18n.t(ctx, R.string.ig_mention_copy_all), () -> {
                        StringBuilder sb = new StringBuilder();
                        for (String u : usernames) sb.append("@").append(u).append("\n");
                        ClipboardManager cm = (ClipboardManager)
                                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("mentions", sb.toString().trim()));
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_all_mentions_copied), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                dlg.show();
            } catch (Throwable t) {
                ModuleLog.line("(IE|Mention) ❌ showMentionsDialog: " + t);
            }
        });
    }

    private static void openProfile(Context ctx, String username) {
        if (ctx == null || username == null || username.isEmpty()) return;

        Activity activity = findActivity(ctx);
        Context launchContext = activity != null ? activity : ctx;
        String encoded = Uri.encode(username);
        String hostPackage = launchContext.getPackageName();

        // Deliver the deep link straight to Instagram's own launcher Activity. Using only an
        // implicit instagram:// intent can resolve successfully yet fail to move the already-open
        // story viewer, so an explicit launch component is much more reliable in the hooked app.
        try {
            Intent intent = launchContext.getPackageManager().getLaunchIntentForPackage(hostPackage);
            if (intent != null) {
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("instagram://user?username=" + encoded));
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if (!(launchContext instanceof Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                launchContext.startActivity(intent);
                ModuleLog.line("(IE|Mention) opened profile @" + username + " via Instagram activity");
                return;
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) Instagram profile deep link failed for @" + username + ": " + t);
        }

        // Fallback: let Android open the Instagram web profile if the host launch Activity cannot
        // be resolved. Do not package-lock this fallback, otherwise a changed IG intent filter can
        // make the tap appear to do nothing.
        try {
            Intent fallback = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.instagram.com/" + encoded + "/"));
            if (!(launchContext instanceof Activity)) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            launchContext.startActivity(fallback);
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) ❌ open profile @" + username + ": " + t);
        }
    }

    // Background for the compact in-story mentions button.
    private static GradientDrawable roundRect(int color, float radiusDp, Context ctx, float dp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * dp);
        return d;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static Context findContext(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Context c) return c;
        if (obj instanceof View v) return v.getContext();

        Context fallback = null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (Context.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    try {
                        Object v = f.get(obj);
                        if (v instanceof Activity a) return a;
                        if (v instanceof Context c && fallback == null) fallback = c;
                    } catch (Throwable ignored) {}
                }
            }

            for (Method method : cls.getDeclaredMethods()) {
                if (method.getParameterCount() != 0
                        || !Context.class.isAssignableFrom(method.getReturnType())) continue;
                try {
                    method.setAccessible(true);
                    Object v = method.invoke(obj);
                    if (v instanceof Activity a) return a;
                    if (v instanceof Context c && fallback == null) fallback = c;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return fallback;
    }

}
