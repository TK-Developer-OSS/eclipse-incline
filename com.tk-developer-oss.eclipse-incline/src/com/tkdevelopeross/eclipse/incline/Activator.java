package com.tkdevelopeross.eclipse.incline;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * The activator class controls the plug-in life cycle
 */
public class Activator extends AbstractUIPlugin {

	// The plug-in ID
	public static final String PLUGIN_ID = "com.tkdevelopeross.eclipse.incline"; //$NON-NLS-1$

	/** チャットビューの ID (plugin.xml と同じ)。 */
	public static final String CHAT_VIEW_ID = PLUGIN_ID + ".chatView"; //$NON-NLS-1$

	/**
	 * このプラグイン自身のダイアログの Shell に付ける目印 (Shell.setData のキー)。
	 * 目印の付いたダイアログは、Claude に読ませないし操作もさせない (許可ダイアログを Claude が自分で押せないように)。
	 */
	public static final String OWN_DIALOG_KEY = PLUGIN_ID + ".ownDialog"; //$NON-NLS-1$

	// The shared instance
	private static Activator plugin;
	
	/**
	 * The constructor
	 */
	public Activator() {
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		super.stop(context);
	}

	/**
	 * Returns the shared instance
	 *
	 * @return the shared instance
	 */
	public static Activator getDefault() {
		return plugin;
	}

	public static void logError(String message, Throwable t) {
		log(new Status(IStatus.ERROR, PLUGIN_ID, message, t));
	}

	public static void logInfo(String message) {
		log(new Status(IStatus.INFO, PLUGIN_ID, message));
	}

	private static void log(IStatus status) {
		Activator a = plugin;
		if (a != null) {
			a.getLog().log(status);
		}
	}

}
