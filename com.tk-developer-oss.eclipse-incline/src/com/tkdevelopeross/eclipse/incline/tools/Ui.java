package com.tkdevelopeross.eclipse.incline.tools;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * ツールから Eclipse の画面に触るための部品。ツールは UI スレッドではないスレッドで動くので、
 * ワークベンチに触る処理は call() で UI スレッドに渡す。
 */
final class Ui {

	private static final int TIMEOUT_SECONDS = 20;

	private Ui() {
	}

	static <T> T call(Callable<T> task) throws Exception {
		return await(submit(task), TIMEOUT_SECONDS * 1000L);
	}

	/**
	 * UI スレッドでの実行を頼む。終わるのを待たない。
	 * モーダルダイアログが開いている間も動くように、syncExec ではなく asyncExec を使う。
	 */
	static <T> CompletableFuture<T> submit(Callable<T> task) {
		CompletableFuture<T> future = new CompletableFuture<>();
		Display display = PlatformUI.getWorkbench().getDisplay();
		Runnable runnable = () -> {
			try {
				future.complete(task.call());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		};
		if (display.getThread() == Thread.currentThread()) {
			runnable.run();
		} else {
			display.asyncExec(runnable);
		}
		return future;
	}

	/**
	 * submit() した処理が終わるのを待つ。処理が投げた例外はそのまま投げ直す。
	 *
	 * @throws TimeoutException 時間内に終わらなかった (処理は取り消されず、UI スレッドで続く)
	 */
	static <T> T await(CompletableFuture<T> future, long timeoutMillis) throws Exception {
		try {
			return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			if (cause instanceof Error) {
				throw (Error) cause;
			}
			throw e;
		} catch (TimeoutException e) {
			throw new TimeoutException("Eclipse's UI thread did not finish this within " + (timeoutMillis + 999) / 1000 //$NON-NLS-1$
					+ " seconds. It may be busy with a long operation."); //$NON-NLS-1$
		}
	}

	/** UI スレッドで呼ぶこと。 */
	static IWorkbenchWindow window() {
		IWorkbench workbench = PlatformUI.getWorkbench();
		IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
		if (window == null && workbench.getWorkbenchWindowCount() > 0) {
			window = workbench.getWorkbenchWindows()[0];
		}
		if (window == null) {
			throw new IllegalStateException("No Eclipse workbench window is open."); //$NON-NLS-1$
		}
		return window;
	}

	/** UI スレッドで呼ぶこと。 */
	static IWorkbenchPage page() {
		IWorkbenchPage page = window().getActivePage();
		if (page == null) {
			throw new IllegalStateException("The Eclipse workbench window has no active page."); //$NON-NLS-1$
		}
		return page;
	}

	/** 長い文章の頭と末尾だけを残す。 */
	static String clip(String text, int maxChars) {
		if (text.length() <= maxChars) {
			return text;
		}
		int half = maxChars / 2;
		return text.substring(0, half) + "\n... (" + (text.length() - 2 * half) + " chars omitted) ...\n" //$NON-NLS-1$ //$NON-NLS-2$
				+ text.substring(text.length() - half);
	}
}
