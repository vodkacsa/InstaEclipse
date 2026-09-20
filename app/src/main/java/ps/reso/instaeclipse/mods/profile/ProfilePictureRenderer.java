package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Draw pixels directly: drawing Instagram's circular Drawable simply draws another circle. */
public final class ProfilePictureRenderer {
    private static final Map<Class<?>, List<Field>> bitmapFields = new ConcurrentHashMap<>();
    private static final Map<View, Boolean> outlines = new WeakHashMap<>();
    private static final Map<View, CapturedBitmap> captured = new WeakHashMap<>();
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF destination = new RectF();

    private static final class CapturedBitmap {
        final WeakReference<Drawable> drawable;
        final WeakReference<Bitmap> bitmap;
        CapturedBitmap(Drawable drawable, Bitmap bitmap) {
            this.drawable = new WeakReference<>(drawable);
            this.bitmap = new WeakReference<>(bitmap);
        }
    }

    public static boolean isProfilePicture(View view) {
        // Instagram often gives the actual image view its own (obfuscated) ID while the stable
        // public ID lives on a wrapper. Never stop just because an intermediate view has an ID.
        View current = view;
        for (int depth = 0; depth <= 5; depth++) {
            if (namedPicture(current)) return true;
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return false;
    }

    private static boolean namedPicture(View view) {
        try {
            String name = view.getResources().getResourceEntryName(view.getId());
            return name.equals("expanded_profile_pic") || name.equals("row_profile_header_imageview")
                    || name.equals("profile_header_imageview");
        } catch (RuntimeException ignored) { return false; }
    }

    static Drawable drawable(View view) {
        if (view instanceof ImageView) return ((ImageView) view).getDrawable();
        try { return (Drawable) view.getClass().getMethod("getDrawable").invoke(view); }
        catch (ReflectiveOperationException | ClassCastException ignored) { return null; }
    }

    public static void capture(View view, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) captured.remove(view);
        else captured.put(view, new CapturedBitmap(drawable(view), bitmap));
    }

    public static void prepare(View view) {
        // Disable outline clipping on the renderer and its nearby wrappers. Newer Instagram
        // builds insert extra containers with their own IDs between expanded_profile_pic and
        // the actual IgImageView/CircularImageView.
        View current = view;
        for (int depth = 0; depth <= 5; depth++) {
            disableOutline(current);
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
    }

    private static void disableOutline(View view) {
        if (!outlines.containsKey(view)) outlines.put(view, view.getClipToOutline());
        if (view.getClipToOutline()) view.setClipToOutline(false);
    }

    public static void refresh(boolean enabled) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> refresh(enabled));
            return;
        }
        for (Map.Entry<View, Boolean> entry : new ArrayList<>(outlines.entrySet())) {
            View view = entry.getKey();
            if (view == null) continue;
            view.setClipToOutline(enabled ? false : entry.getValue());
            view.invalidate();
        }
        if (!enabled) outlines.clear();
    }

    public static boolean draw(View view, Canvas canvas) {
        Drawable current = drawable(view);
        Bitmap bitmap = bitmapFromDrawable(current);
        if (bitmap == null) {
            CapturedBitmap fallback = captured.get(view);
            if (fallback != null && current != null && fallback.drawable.get() == current) bitmap = fallback.bitmap.get();
        }
        if (bitmap == null || bitmap.isRecycled()) return false;
        int width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        int height = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
        if (width <= 0 || height <= 0) return false;
        float scale = Math.min((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight());
        float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
        float left = view.getPaddingLeft() + (width - w) / 2;
        float top = view.getPaddingTop() + (height - h) / 2;
        destination.set(left, top, left + w, top + h);
        paint.setAlpha(current == null ? 255 : current.getAlpha());
        paint.setColorFilter(current == null ? null : current.getColorFilter());
        // Do not invoke Drawable.draw, mutate its bounds, or reuse its circular BitmapShader.
        canvas.drawBitmap(bitmap, null, destination, paint);
        return true;
    }

    static Bitmap bitmapFromDrawable(Drawable drawable) {
        return bitmapFromDrawable(drawable, Collections.newSetFromMap(new IdentityHashMap<>()), 4);
    }

    private static Bitmap bitmapFromDrawable(Drawable drawable, Set<Drawable> seen, int depth) {
        if (drawable == null || depth == 0 || !seen.add(drawable)) return null;
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            return bitmap != null && !bitmap.isRecycled() ? bitmap : null;
        }
        if (drawable.getCurrent() != drawable) {
            Bitmap bitmap = bitmapFromDrawable(drawable.getCurrent(), seen, depth - 1);
            if (bitmap != null) return bitmap;
        }
        // Circular drawables keep the original bitmap, often in an obfuscated private field.
        List<Field> fields = bitmapFields.computeIfAbsent(drawable.getClass(), type -> {
            List<Field> result = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Drawable.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (!Bitmap.class.isAssignableFrom(f.getType()) && !Drawable.class.isAssignableFrom(f.getType())) continue;
                    try { f.setAccessible(true); result.add(f); } catch (RuntimeException ignored) { }
                }
            }
            return result;
        });
        for (Field field : fields) if (Bitmap.class.isAssignableFrom(field.getType())) {
            try {
                Bitmap bitmap = (Bitmap) field.get(drawable);
                if (bitmap != null && !bitmap.isRecycled()) return bitmap;
            } catch (IllegalAccessException ignored) { }
        }
        for (Field field : fields) if (Drawable.class.isAssignableFrom(field.getType())) {
            try {
                Bitmap bitmap = bitmapFromDrawable((Drawable) field.get(drawable), seen, depth - 1);
                if (bitmap != null) return bitmap;
            } catch (IllegalAccessException ignored) { }
        }
        return null;
    }
}
