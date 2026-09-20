package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ProfilePictureRendererTest {
    // Represents Instagram's custom drawable: its draw() masks the original bitmap to a circle.
    private static class CircularDrawable extends Drawable {
        private final Bitmap obfuscatedBitmap;
        CircularDrawable(Bitmap bitmap) { obfuscatedBitmap = bitmap; }
        @Override public void draw(Canvas canvas) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new BitmapShader(obfuscatedBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            canvas.drawCircle(40, 40, 40, paint);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return obfuscatedBitmap.getWidth(); }
        @Override public int getIntrinsicHeight() { return obfuscatedBitmap.getHeight(); }
    }
    private static class DerivedCircularDrawable extends CircularDrawable {
        DerivedCircularDrawable(Bitmap bitmap) { super(bitmap); }
    }
    private ImageView view(Drawable drawable) {
        ImageView view = new ImageView(RuntimeEnvironment.getApplication());
        view.setImageDrawable(drawable); view.layout(0, 0, 80, 80);
        return view;
    }
    private Bitmap solid(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED); return bitmap;
    }
    private Bitmap output() { return Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888); }

    @Test public void drawsCornersThatTheCircularDrawableMasksOut() {
        Drawable circular = new CircularDrawable(solid(80, 80));
        Bitmap oldOutput = output(); circular.draw(new Canvas(oldOutput));
        assertEquals(Color.TRANSPARENT, oldOutput.getPixel(2, 2));
        Bitmap fullOutput = output();
        assertTrue(ProfilePictureRenderer.draw(view(circular), new Canvas(fullOutput)));
        for (int x : new int[]{2, 77}) for (int y : new int[]{2, 77}) assertEquals(Color.RED, fullOutput.getPixel(x, y));
    }
    @Test public void fitsEntireNonSquareBitmapAndPreservesBothEdges() {
        Bitmap source = solid(160, 80);
        Canvas sourceCanvas = new Canvas(source); Paint blue = new Paint(); blue.setColor(Color.BLUE);
        sourceCanvas.drawRect(140, 0, 160, 80, blue);
        Bitmap result = output();
        assertTrue(ProfilePictureRenderer.draw(view(new CircularDrawable(source)), new Canvas(result)));
        assertEquals(Color.TRANSPARENT, result.getPixel(40, 5));
        assertEquals(Color.RED, result.getPixel(2, 40));
        assertEquals(Color.BLUE, result.getPixel(77, 40));
        assertEquals(Color.TRANSPARENT, result.getPixel(40, 75));
    }
    @Test public void findsInheritedBitmapWithoutChangingDrawableBoundsOrCallback() {
        Drawable image = new DerivedCircularDrawable(solid(80, 80));
        ImageView view = view(image); image.setBounds(7, 9, 57, 59);
        Drawable.Callback callback = image.getCallback();
        ProfilePictureRenderer.draw(view, new Canvas(output()));
        assertEquals(new android.graphics.Rect(7, 9, 57, 59), image.getBounds());
        assertSame(callback, image.getCallback());
    }
    @Test public void capturedBitmapCannotLeakIntoRecycledViewWithDifferentDrawable() {
        ImageView view = view(new ColorDrawable(Color.BLACK));
        ProfilePictureRenderer.capture(view, solid(80, 80));
        assertTrue(ProfilePictureRenderer.draw(view, new Canvas(output())));
        view.setImageDrawable(new ColorDrawable(Color.BLUE));
        assertFalse(ProfilePictureRenderer.draw(view, new Canvas(output())));
        ProfilePictureRenderer.capture(view, null);
        assertFalse(ProfilePictureRenderer.draw(view, new Canvas(output())));
    }
    @Test public void disablingRestoresOutlineClipping() {
        ImageView view = view(new CircularDrawable(solid(80, 80)));
        view.setClipToOutline(true);
        ProfilePictureRenderer.prepare(view); assertFalse(view.getClipToOutline());
        ProfilePictureRenderer.refresh(false); assertTrue(view.getClipToOutline());
    }
    @Test public void respectsPaddingAndLeavesUnknownOrRecycledImagesAlone() {
        ImageView view = view(new CircularDrawable(solid(80, 80))); view.setPadding(10, 10, 10, 10);
        Bitmap result = output(); assertTrue(ProfilePictureRenderer.draw(view, new Canvas(result)));
        assertEquals(Color.TRANSPARENT, result.getPixel(5, 5));
        assertEquals(Color.RED, result.getPixel(12, 12));
        assertFalse(ProfilePictureRenderer.draw(view(new ColorDrawable(Color.RED)), new Canvas(output())));
        Bitmap recycled = solid(80, 80); recycled.recycle();
        assertNull(ProfilePictureRenderer.bitmapFromDrawable(new CircularDrawable(recycled)));
    }
}
