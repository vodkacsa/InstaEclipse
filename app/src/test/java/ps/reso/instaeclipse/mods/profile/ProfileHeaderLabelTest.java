package ps.reso.instaeclipse.mods.profile;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ProfileHeaderLabelTest {
    private Context context() { return RuntimeEnvironment.getApplication(); }
    private TextView threads() {
        TextView view = new TextView(context());
        view.setText("Threads"); view.setContentDescription("Open Threads profile");
        view.setTextColor(0xfffafafa);
        return view;
    }
    private void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }
    @Test public void sitsBesideThreadsAsMeasuredSiblingAndDoesNotDuplicate() {
        LinearLayout header = new LinearLayout(context()); header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(context());
        TextView threads = threads(); row.addView(threads); header.addView(row);
        layout(header);
        ProfileHeaderLabel label = new ProfileHeaderLabel();
        label.renderHeader(header, "Follows you"); layout(header);
        assertEquals(2, row.getChildCount());
        TextView badge = (TextView) row.getChildAt(1);
        assertSame(row, badge.getParent());
        assertTrue(badge.getLeft() >= threads.getRight());
        assertEquals(threads.getCurrentTextColor(), badge.getCurrentTextColor());
        assertEquals(0f, badge.getElevation(), 0f);
        assertNull(badge.getBackground());
        label.renderHeader(header, "Friends");
        assertEquals(2, row.getChildCount());
        assertEquals("Friends", badge.getText().toString());
        label.clear(); assertEquals(1, row.getChildCount());
        assertEquals("Threads", threads.getText().toString());
    }
    @Test public void nativeTextFallbackKeepsSpansAndRestoresOriginal() {
        FrameLayout header = new FrameLayout(context());
        TextView threads = threads();
        SpannableString original = new SpannableString("Threads");
        StyleSpan bold = new StyleSpan(Typeface.BOLD); original.setSpan(bold, 0, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        threads.setText(original); header.addView(threads);
        ProfileHeaderLabel label = new ProfileHeaderLabel(); label.renderHeader(header, "Follows you");
        assertEquals(1, header.getChildCount());
        assertEquals("Threads  ·  Follows you", threads.getText().toString());
        assertEquals(0, ((Spanned) threads.getText()).getSpanStart(bold));
        label.clear();
        assertEquals("Threads", threads.getText().toString());
        assertEquals(0, ((Spanned) threads.getText()).getSpanStart(bold));
    }
    @Test public void removalDoesNotOverwriteNewTextFromInstagram() {
        FrameLayout header = new FrameLayout(context()); TextView threads = threads(); header.addView(threads);
        ProfileHeaderLabel label = new ProfileHeaderLabel(); label.renderHeader(header, "Friends");
        threads.setText("New profile name"); label.clear();
        assertEquals("New profile name", threads.getText().toString());
    }
    @Test public void replacingHeaderDoesNotLeaveLabelOnPreviousProfile() {
        LinearLayout first = new LinearLayout(context()), second = new LinearLayout(context());
        first.addView(threads()); second.addView(threads());
        ProfileHeaderLabel label = new ProfileHeaderLabel();
        label.renderHeader(first, "Friends"); label.renderHeader(second, "Follows you");
        assertEquals(1, first.getChildCount()); assertEquals(2, second.getChildCount());
        label.renderHeader(second, null); assertEquals(1, second.getChildCount());
    }
    @Test public void narrowRowUsesFlowingLineBelowInsteadOfCoveringButtons() {
        LinearLayout header = new LinearLayout(context()); header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(context()); row.addView(threads());
        header.addView(row, new LinearLayout.LayoutParams(30, -2)); layout(header);
        ProfileHeaderLabel label = new ProfileHeaderLabel(); label.renderHeader(header, "Follows you");
        assertEquals(1, row.getChildCount()); assertEquals(2, header.getChildCount());
        assertTrue(header.getChildAt(1) instanceof TextView);
    }
}
