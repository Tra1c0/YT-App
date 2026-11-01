import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Xml;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.xmlpull.v1.XmlSerializer;

/**
 * A shell-side UI driver for the emulator checks, never included in the app. A single
 * process owns each touch stream, including its downTime. Starting `input motionevent`
 * for every MOVE used to turn ordinary drags into long presses on a busy emulator.
 * Accessibility snapshots do not wait for a playing video or an animation to become idle.
 * This uses the same platform UiAutomation connection as Android's uiautomator command.
 */
public final class YtInput {
    private final UiAutomation automation;
    private long downTime;
    private float lastX, lastY;

    private YtInput(UiAutomation automation) { this.automation = automation; }

    public static void main(String[] args) throws Exception {
        HandlerThread thread = new HandlerThread("yt-input");
        thread.start();
        Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        Constructor<UiAutomation> constructor = UiAutomation.class.getDeclaredConstructor(Looper.class, connectionType);
        constructor.setAccessible(true);
        UiAutomation automation = constructor.newInstance(thread.getLooper(), connection);
        Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 0;
        automation.setServiceInfo(info);
        YtInput driver = new YtInput(automation);
        System.out.println("YTINPUT READY");
        System.out.flush();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String command;
            while ((command = input.readLine()) != null) {
                if ("QUIT".equals(command)) break;
                try {
                    String result = driver.command(command);
                    reply("OK", result);
                } catch (Exception e) {
                    reply("ERR", e.toString());
                }
            }
        } finally {
            if (driver.downTime != 0) driver.touch(MotionEvent.ACTION_CANCEL, driver.lastX, driver.lastY);
            Method disconnect = UiAutomation.class.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(automation);
            thread.quitSafely();
        }
    }

    private static void reply(String status, String value) {
        System.out.println("YTINPUT " + status + " " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        System.out.flush();
    }

    private String command(String line) throws Exception {
        String[] words = line.trim().split("\\s+");
        if ("DUMP".equals(words[0])) return dump();
        if (words.length < 3) throw new IllegalArgumentException("Expected a touch and x y");
        float x = Float.parseFloat(words[1]), y = Float.parseFloat(words[2]);
        if ("START".equals(words[0]) && words.length == 5) {
            touch(MotionEvent.ACTION_DOWN, x, y);
            touch(MotionEvent.ACTION_MOVE, Float.parseFloat(words[3]), Float.parseFloat(words[4]));
        } else if ("DOWN".equals(words[0])) touch(MotionEvent.ACTION_DOWN, x, y);
        else if ("MOVE".equals(words[0])) touch(MotionEvent.ACTION_MOVE, x, y);
        else if ("UP".equals(words[0])) touch(MotionEvent.ACTION_UP, x, y);
        else if ("TAP".equals(words[0])) {
            touch(MotionEvent.ACTION_DOWN, x, y);
            touch(MotionEvent.ACTION_UP, x, y);
        } else throw new IllegalArgumentException("Unknown command " + words[0]);
        return "";
    }

    private void touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            if (downTime != 0) touch(MotionEvent.ACTION_CANCEL, lastX, lastY);
            downTime = now;
        } else if (downTime == 0) throw new IllegalStateException("Touch without DOWN");
        MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("Input was rejected");
        } finally {
            event.recycle();
            lastX = x;
            lastY = y;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downTime = 0;
        }
    }

    private String dump() throws Exception {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) throw new IllegalStateException("No active window");
        StringWriter writer = new StringWriter();
        XmlSerializer xml = Xml.newSerializer();
        xml.setOutput(writer);
        xml.startTag("", "hierarchy");
        try { node(xml, root, 0); } finally { root.recycle(); }
        xml.endTag("", "hierarchy");
        xml.flush();
        return writer.toString();
    }

    private void node(XmlSerializer xml, AccessibilityNodeInfo node, int depth) throws Exception {
        if (depth > 80) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.startTag("", "node");
        xml.attribute("", "text", value(node.getText()));
        xml.attribute("", "content-desc", value(node.getContentDescription()));
        xml.attribute("", "resource-id", value(node.getViewIdResourceName()));
        xml.attribute("", "class", value(node.getClassName()));
        xml.attribute("", "checkable", Boolean.toString(node.isCheckable()));
        xml.attribute("", "checked", Boolean.toString(node.isChecked()));
        xml.attribute("", "enabled", Boolean.toString(node.isEnabled()));
        xml.attribute("", "clickable", Boolean.toString(node.isClickable()));
        xml.attribute("", "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (child.isVisibleToUser()) node(xml, child, depth + 1); }
            finally { child.recycle(); }
        }
        xml.endTag("", "node");
    }

    private static String value(CharSequence value) {
        // XmlSerializer rejects these control characters, which can occur in video metadata.
        return value == null ? "" : value.toString().replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }
}
