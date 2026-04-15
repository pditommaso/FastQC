package uk.ac.babraham.FastQC.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

/**
 * GraalVM native-image Feature for headless AWT support.
 *
 * FastQC graph classes extend JPanel, which triggers AWT toolkit
 * initialization even though we never render to screen. These
 * substitutions prevent the native AWT library from being loaded.
 */
public class AWTFeature implements Feature {

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        System.setProperty("java.awt.headless", "true");
        RuntimeClassInitialization.initializeAtBuildTime("sun.awt.PlatformGraphicsInfo");
    }

    @Override
    public String getDescription() {
        return "Substitutes AWT native library loading for headless native-image";
    }
}

@TargetClass(java.awt.Toolkit.class)
final class Target_java_awt_Toolkit {
    @Substitute
    private static void loadLibraries() {}

    @Substitute
    private static void initIDs() {}

    @Substitute
    public static synchronized java.awt.Toolkit getDefaultToolkit() {
        return null;
    }
}

@TargetClass(java.awt.GraphicsEnvironment.class)
final class Target_java_awt_GraphicsEnvironment {
    @Substitute
    public static boolean isHeadless() { return true; }

    @Substitute
    private static boolean getHeadlessProperty() { return true; }
}

@TargetClass(className = "sun.awt.PlatformGraphicsInfo")
final class Target_sun_awt_PlatformGraphicsInfo {
    @Substitute
    public static boolean isInAquaSession() { return false; }
}

@TargetClass(javax.swing.JPanel.class)
final class Target_javax_swing_JPanel {
    @Substitute
    public void updateUI() {}
}

@TargetClass(javax.swing.JComponent.class)
final class Target_javax_swing_JComponent {
    @Substitute
    public void updateUI() {}
}
