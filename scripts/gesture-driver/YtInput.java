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
import java.util.List;
import java.util.regex.Pattern;
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
    private Pattern findPattern;
    private int findRemaining;

    private YtInput(UiAutomation automation) { this.automation = automation; }

    public static void main(String[] args) throws Exception {
        // Android 16's AccessibilityInteractionClient also posts to the main
        // looper. app_process supplies none, so keep one running while the
        // command stream blocks on stdin in its own thread.
        Looper.prepareMainLooper();
        new Thread(() -> {
            int status = 0;
            try {
                runDriver();
            } catch (Throwable error) {
                error.printStackTrace(System.err);
                status = 1;
            }
            System.exit(status);
        }, "yt-input-commands").start();
        Looper.loop();
    }

    private static void runDriver() throws Exception {
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
                int separator = command.indexOf(' ');
                if (separator < 1) continue;
                String request = command.substring(0, separator);
                try {
                    String result = driver.command(command.substring(separator + 1));
                    reply(request, "OK", result);
                } catch (Exception e) {
                    reply(request, "ERR", e.toString());
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

    private static void reply(String request, String status, String value) {
        System.out.println("YTINPUT " + request + " " + status + " " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        System.out.flush();
    }

    private String command(String line) throws Exception {
        String[] words = line.trim().split("\\s+");
        if ("DUMP".equals(words[0])) return dump();
        if ("FIND".equals(words[0]) && words.length == 3) {
            return find(new String(Base64.getDecoder().decode(words[1]), StandardCharsets.UTF_8), Integer.parseInt(words[2]));
        }
        if (words.length < 3) throw new IllegalArgumentException("Expected a touch and x y");
        float x = Float.parseFloat(words[1]), y = Float.parseFloat(words[2]);
        if ("FLING".equals(words[0]) && words.length == 6) {
            float endX = Float.parseFloat(words[3]), endY = Float.parseFloat(words[4]);
            int duration = Math.max(16, Math.min(1000, Integer.parseInt(words[5])));
            long start = SystemClock.uptimeMillis();
            touchAt(MotionEvent.ACTION_DOWN, x, y, false, start);
            int steps = Math.max(4, duration / 8);
            for (int i = 1; i <= steps; i++) {
                long wait = start + duration * i / steps - SystemClock.uptimeMillis();
                if (wait > 0) SystemClock.sleep(wait);
                touchAt(MotionEvent.ACTION_MOVE, x + (endX - x) * i / steps, y + (endY - y) * i / steps, false, start + duration * i / steps);
            }
            long upAt = start + duration + 1;
            if (upAt > SystemClock.uptimeMillis()) SystemClock.sleep(upAt - SystemClock.uptimeMillis());
            touchAt(MotionEvent.ACTION_UP, endX, endY, true, upAt);
        } else if ("START".equals(words[0]) && words.length == 5) {
            touch(MotionEvent.ACTION_DOWN, x, y, false);
            touch(MotionEvent.ACTION_MOVE, Float.parseFloat(words[3]), Float.parseFloat(words[4]));
        } else if ("DOWN".equals(words[0])) touch(MotionEvent.ACTION_DOWN, x, y);
        else if ("MOVE".equals(words[0])) touch(MotionEvent.ACTION_MOVE, x, y);
        else if ("UP".equals(words[0])) touch(MotionEvent.ACTION_UP, x, y);
        else if ("DOUBLE".equals(words[0])) {
            touch(MotionEvent.ACTION_DOWN, x, y, false);
            touch(MotionEvent.ACTION_UP, x, y, false);
            SystemClock.sleep(60);
            touch(MotionEvent.ACTION_DOWN, x, y, false);
            touch(MotionEvent.ACTION_UP, x, y);
        } else if ("TAP".equals(words[0])) {
            touch(MotionEvent.ACTION_DOWN, x, y, false);
            touch(MotionEvent.ACTION_UP, x, y);
        } else throw new IllegalArgumentException("Unknown command " + words[0]);
        return "";
    }

    private void touch(int action, float x, float y) {
        touch(action, x, y, true);
    }

    private void touch(int action, float x, float y, boolean sync) {
        touchAt(action, x, y, sync, SystemClock.uptimeMillis());
    }

    private void touchAt(int action, float x, float y, boolean sync, long now) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (downTime != 0) touch(MotionEvent.ACTION_CANCEL, lastX, lastY);
            downTime = now;
        } else if (downTime == 0) throw new IllegalStateException("Touch without DOWN");
        MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!automation.injectInputEvent(event, sync)) throw new IllegalStateException("Input was rejected");
        } finally {
            event.recycle();
            lastX = x;
            lastY = y;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downTime = 0;
        }
    }

    private String dump() throws Exception {
        AccessibilityNodeInfo root = currentRoot();
        if (root == null) throw new IllegalStateException("No active window");
        StringWriter writer = new StringWriter();
        XmlSerializer xml = Xml.newSerializer();
        xml.setOutput(writer);
        xml.startTag("", "hierarchy");
        Rect viewport = new Rect();
        root.getBoundsInScreen(viewport);
        try { node(xml, root, 0, viewport); } finally { root.recycle(); }
        xml.endTag("", "hierarchy");
        xml.flush();
        return writer.toString();
    }

    private AccessibilityNodeInfo currentRoot() throws Exception {
        // Older Android versions have no clearCache(), and refresh on the accessibility events.
        try { UiAutomation.class.getMethod("clearCache").invoke(automation); }
        catch (NoSuchMethodException ignored) { }
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root != null && !root.refresh()) { root.recycle(); return null; }
        return root;
    }

    /** Find one target without serializing the whole, constantly changing screen. */
    private String find(String expression, int nth) throws Exception {
        AccessibilityNodeInfo root = currentRoot();
        if (root == null) return "NOT_FOUND";
        findPattern = Pattern.compile(expression);
        findRemaining = Math.max(1, nth);
        Rect viewport = new Rect();
        root.getBoundsInScreen(viewport);
        try {
            // Platform views (player, time bar, transient chips) have a direct ID query.
            java.util.regex.Matcher nativeId = Pattern.compile("^(?:id/)?([A-Za-z0-9_]+)\\$$").matcher(expression);
            if (nativeId.matches() && root.getPackageName() != null) {
                String rootPackage = root.getPackageName().toString();
                String[] packages = "app.tra1c0.yt".equals(rootPackage) ? new String[] { rootPackage }
                    : new String[] { rootPackage, "app.tra1c0.yt" };
                for (String packageName : packages) {
                    findRemaining = Math.max(1, nth);
                    String id = packageName + ":id/" + nativeId.group(1);
                    List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(id);
                    String result = null;
                    for (AccessibilityNodeInfo match : matches) {
                        try {
                            if (result == null && match.refresh() && match.isVisibleToUser()) {
                                Rect box = visibleBounds(match, viewport);
                                if (!box.isEmpty() && --findRemaining == 0) result = coordinates(box);
                            }
                        } finally { match.recycle(); }
                    }
                    if (result != null) return result;
                }
                findRemaining = Math.max(1, nth);
            }
            String result = findNode(root, viewport, 0);
            if (result == null) System.err.println("FIND miss " + expression + " window=" + root.getWindowId()
                + " bounds=" + viewport + " package=" + root.getPackageName() + " children=" + root.getChildCount());
            return result == null ? "NOT_FOUND" : result;
        } finally { root.recycle(); }
    }

    private String findNode(AccessibilityNodeInfo node, Rect clip, int depth) {
        if (depth > 80) return null;
        Rect bounds = visibleBounds(node, clip);
        if (bounds.isEmpty()) return null;
        if ((findPattern.matcher(value(node.getViewIdResourceName())).find()
                || findPattern.matcher(value(node.getText())).find()
                || findPattern.matcher(value(node.getContentDescription())).find()) && --findRemaining == 0) {
            return coordinates(bounds);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (!child.isVisibleToUser()) continue;
                String result = findNode(child, node.isScrollable() ? bounds : clip, depth + 1);
                if (result != null) return result;
            } finally { child.recycle(); }
        }
        return null;
    }

    private static Rect visibleBounds(AccessibilityNodeInfo node, Rect clip) {
        Rect box = new Rect();
        node.getBoundsInScreen(box);
        if (!box.intersect(clip)) box.setEmpty();
        return box;
    }

    private static String coordinates(Rect box) {
        return box.left + " " + box.top + " " + box.right + " " + box.bottom;
    }

    private void node(XmlSerializer xml, AccessibilityNodeInfo node, int depth, Rect clip) throws Exception {
        if (depth > 80) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.intersect(clip)) bounds.setEmpty();
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
            try { if (child.isVisibleToUser()) node(xml, child, depth + 1, node.isScrollable() ? bounds : clip); }
            finally { child.recycle(); }
        }
        xml.endTag("", "node");
    }

    private static String value(CharSequence value) {
        // XmlSerializer rejects these control characters, which can occur in video metadata.
        return value == null ? "" : value.toString().replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }
}
