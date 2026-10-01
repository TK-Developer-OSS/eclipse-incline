package com.tkdevelopeross.eclipse.incline.selftest;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tkdevelopeross.eclipse.incline.Activator;
import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.tools.EclipseTools;
import com.tkdevelopeross.eclipse.incline.ui.ChatView;

/**
 * 実際のワークベンチの中でツールとチャットビューを動かす自己テスト。
 * システムプロパティ incline.selftest に手順ファイル (JSON) のパスを付けて Eclipse を起動すると、
 * 起動後に手順を順に実行し、結果を 1 行ずつファイルに書く。プロパティがなければ何もしない。
 * ツールは claude から呼ばれるときと同じ経路 (McpServer.handle) で呼ぶ。
 *
 * 手順ファイル: { "output": 結果を書くファイル, "timeoutSeconds": 全体の制限時間, "steps": [ 手順, ... ] }
 * 手順は次のどれか:
 * <pre>
 * {"tool": "名前", "args": {...}}                 ツールを呼ぶ
 * {"sleep": ミリ秒}
 * {"window": [幅, 高さ]}                          ワークベンチのウィンドウの大きさを変える
 * {"screenshot": "保存先.png", "of": "window|top|own"}  ウィンドウ / 一番手前のシェル / このプラグインのダイアログを画像にする
 * {"chat": "発言"}                                チャットビューで送信する (応答は待たない)
 * {"waitChat": 秒}                                応答が終わるのを待ち、会話欄の文字を結果にする
 * {"chatCommand": "new|resume"}                   新しいセッションにする / 前回の会話を再開する
 * {"waitOwnDialog": 秒}                           このプラグインのダイアログ (許可ダイアログ) が開くのを待つ
 * {"pressOwnDialog": "ボタンの文字"}              そのダイアログのボタンを押す
 * {"exit": true}                                  Eclipse を閉じる
 * </pre>
 */
public class SelfTest implements IStartup {

	private static final String PROPERTY = "incline.selftest"; //$NON-NLS-1$
	private static final int UI_TIMEOUT_SECONDS = 60;

	private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

	@Override
	public void earlyStartup() {
		String plan = System.getProperty(PROPERTY);
		if (plan == null || plan.isBlank()) {
			return;
		}
		Thread thread = new Thread(() -> run(new File(plan)), "incline-selftest"); //$NON-NLS-1$
		thread.setDaemon(true);
		thread.start();
	}

	private void run(File planFile) {
		Path output = null;
		try {
			JsonObject plan = gson.fromJson(new String(Files.readAllBytes(planFile.toPath()), StandardCharsets.UTF_8),
					JsonObject.class);
			output = Paths.get(plan.get("output").getAsString()); //$NON-NLS-1$
			Files.deleteIfExists(output);
			startWatchdog(plan.has("timeoutSeconds") ? plan.get("timeoutSeconds").getAsInt() : 300, output); //$NON-NLS-1$ //$NON-NLS-2$

			McpServer server = EclipseTools.createServer();
			JsonArray steps = plan.getAsJsonArray("steps"); //$NON-NLS-1$
			for (int i = 0; i < steps.size(); i++) {
				JsonObject step = steps.get(i).getAsJsonObject();
				JsonObject result = new JsonObject();
				result.addProperty("n", i + 1); //$NON-NLS-1$
				result.add("step", step); //$NON-NLS-1$
				long start = System.currentTimeMillis();
				try {
					execute(server, step, i + 1, result);
				} catch (Throwable t) {
					result.addProperty("failed", stackTrace(t)); //$NON-NLS-1$
				}
				result.addProperty("ms", System.currentTimeMillis() - start); //$NON-NLS-1$
				append(output, gson.toJson(result));
			}
			append(output, "{\"finished\":true}"); //$NON-NLS-1$
		} catch (Throwable t) {
			Activator.logError("自己テストを実行できませんでした: " + planFile, t);
			if (output != null) {
				append(output, "{\"aborted\":" + gson.toJson(stackTrace(t)) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			// 手順を実行できないまま Eclipse を開きっぱなしにしない
			Runtime.getRuntime().halt(4);
		}
	}

	private void execute(McpServer server, JsonObject step, int number, JsonObject result) throws Exception {
		if (step.has("tool")) { //$NON-NLS-1$
			String tool = step.get("tool").getAsString(); //$NON-NLS-1$
			JsonObject arguments = step.has("args") ? step.getAsJsonObject("args") : new JsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
			JsonObject params = new JsonObject();
			params.addProperty("name", tool); //$NON-NLS-1$
			params.add("arguments", arguments); //$NON-NLS-1$
			JsonObject request = new JsonObject();
			request.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
			request.addProperty("id", number); //$NON-NLS-1$
			request.addProperty("method", "tools/call"); //$NON-NLS-1$ //$NON-NLS-2$
			request.add("params", params); //$NON-NLS-1$
			JsonObject response = server.handle(request);
			// 確認なしで通るツールかどうかも記録する (チャットビューが許可ダイアログを出すかどうか)
			result.addProperty("auto", server.isAutoAllowed("mcp__" + server.getName() + "__" + tool, arguments)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (response.has("error")) { //$NON-NLS-1$
				result.add("rpcError", response.get("error")); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			JsonObject toolResult = response.getAsJsonObject("result"); //$NON-NLS-1$
			result.addProperty("isError", toolResult.get("isError").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
			result.addProperty("text", //$NON-NLS-1$
					toolResult.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (step.has("sleep")) { //$NON-NLS-1$
			Thread.sleep(step.get("sleep").getAsLong()); //$NON-NLS-1$
		} else if (step.has("window")) { //$NON-NLS-1$
			JsonArray size = step.getAsJsonArray("window"); //$NON-NLS-1$
			ui(() -> {
				Shell shell = windowShell();
				shell.setMaximized(false);
				shell.setBounds(40, 40, size.get(0).getAsInt(), size.get(1).getAsInt());
				return null;
			});
		} else if (step.has("screenshot")) { //$NON-NLS-1$
			String path = step.get("screenshot").getAsString(); //$NON-NLS-1$
			String of = step.has("of") ? step.get("of").getAsString() : "window"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			boolean fromScreen = step.has("fromScreen") && step.get("fromScreen").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
			result.addProperty("text", ui(() -> screenshot(path, of, fromScreen))); //$NON-NLS-1$
		} else if (step.has("chat")) { //$NON-NLS-1$
			String prompt = step.get("chat").getAsString(); //$NON-NLS-1$
			ui(() -> {
				chatView().submitPrompt(prompt);
				return null;
			});
		} else if (step.has("chatCommand")) { //$NON-NLS-1$
			String command = step.get("chatCommand").getAsString(); //$NON-NLS-1$
			ui(() -> {
				if ("resume".equals(command)) { //$NON-NLS-1$
					chatView().resumeLastSession();
				} else {
					chatView().startNewSession();
				}
				return null;
			});
		} else if (step.has("waitChat")) { //$NON-NLS-1$
			long deadline = System.currentTimeMillis() + step.get("waitChat").getAsLong() * 1000; //$NON-NLS-1$
			boolean busy = true;
			while (busy && System.currentTimeMillis() < deadline) {
				Thread.sleep(500);
				busy = ui(() -> chatView().isBusy());
			}
			result.addProperty("stillBusy", busy); //$NON-NLS-1$
			result.addProperty("deltas", ui(() -> chatView().getDeltaCount())); //$NON-NLS-1$
			result.addProperty("text", ui(() -> chatView().getTranscript())); //$NON-NLS-1$
		} else if (step.has("waitOwnDialog")) { //$NON-NLS-1$
			long deadline = System.currentTimeMillis() + step.get("waitOwnDialog").getAsLong() * 1000; //$NON-NLS-1$
			String title = null;
			while (title == null && System.currentTimeMillis() < deadline) {
				Thread.sleep(300);
				title = ui(() -> {
					Shell dialog = ownDialog();
					return dialog == null ? null : dialog.getText();
				});
			}
			result.addProperty("text", title == null ? "(no dialog appeared)" : "dialog: " + title); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} else if (step.has("pressOwnDialog")) { //$NON-NLS-1$
			String label = step.get("pressOwnDialog").getAsString(); //$NON-NLS-1$
			result.addProperty("text", ui(() -> { //$NON-NLS-1$
				Shell dialog = ownDialog();
				Button button = dialog == null ? null : findButton(dialog, label);
				if (button == null) {
					return "no button " + label; //$NON-NLS-1$
				}
				button.notifyListeners(SWT.Selection, new Event());
				return "pressed " + label; //$NON-NLS-1$
			}));
		} else if (step.has("exit")) { //$NON-NLS-1$
			Display display = PlatformUI.getWorkbench().getDisplay();
			display.asyncExec(() -> PlatformUI.getWorkbench().close());
		} else {
			throw new IllegalArgumentException("unknown step"); //$NON-NLS-1$
		}
	}

	// ---- 画面 ----

	private static <T> T ui(Callable<T> task) throws Exception {
		CompletableFuture<T> future = new CompletableFuture<>();
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			try {
				future.complete(task.call());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof Exception) {
				throw (Exception) e.getCause();
			}
			throw e;
		}
	}

	private static Shell windowShell() {
		IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
		if (windows.length == 0) {
			throw new IllegalStateException("no workbench window"); //$NON-NLS-1$
		}
		return windows[0].getShell();
	}

	private static ChatView chatView() throws Exception {
		IWorkbenchPage page = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage();
		return (ChatView) page.showView(Activator.CHAT_VIEW_ID);
	}

	private static Shell ownDialog() {
		for (Shell shell : Display.getCurrent().getShells()) {
			if (!shell.isDisposed() && shell.isVisible() && shell.getData(Activator.OWN_DIALOG_KEY) != null) {
				return shell;
			}
		}
		return null;
	}

	/** 一番手前にあるシェル。ダイアログがなければワークベンチのウィンドウ。 */
	private static Shell topShell() {
		Display display = Display.getCurrent();
		Shell window = windowShell();
		Shell active = display.getActiveShell();
		if (active != null && active != window) {
			return active;
		}
		Shell top = window;
		for (Shell shell : display.getShells()) {
			if (!shell.isDisposed() && shell.isVisible() && shell != window && !shell.getText().isEmpty()) {
				top = shell;
			}
		}
		return top;
	}

	private static Button findButton(Composite parent, String label) {
		for (Control child : parent.getChildren()) {
			if (child instanceof Button && label.equals(((Button) child).getText())) {
				return (Button) child;
			}
			if (child instanceof Composite) {
				Button found = findButton((Composite) child, label);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static String screenshot(String path, String of, boolean fromScreen) {
		Shell shell = "own".equals(of) ? ownDialog() : "top".equals(of) ? topShell() : windowShell(); //$NON-NLS-1$ //$NON-NLS-2$
		if (shell == null) {
			return "no such shell: " + of; //$NON-NLS-1$
		}
		Display display = shell.getDisplay();
		Rectangle bounds = shell.getBounds();
		Image image = new Image(display, bounds.width, bounds.height);
		String how = "printed"; //$NON-NLS-1$
		boolean printed = false;
		if (!fromScreen) {
			// ほかのウィンドウに隠れていても、ウィンドウ自身に描かせる
			GC gc = new GC(image);
			printed = shell.print(gc);
			gc.dispose();
		}
		if (!printed) {
			GC screen = new GC(display);
			screen.copyArea(image, bounds.x, bounds.y);
			screen.dispose();
			how = "copied from the screen"; //$NON-NLS-1$
		}
		ImageLoader loader = new ImageLoader();
		loader.data = new ImageData[] { image.getImageData() };
		loader.save(path, SWT.IMAGE_PNG);
		image.dispose();
		return how + " \"" + shell.getText() + "\" " + bounds.width + "x" + bounds.height + " -> " + path; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	// ---- 結果の記録 ----

	/** 画面がダイアログで止まっても必ず終わるように、制限時間を過ぎたら Java ごと止める。 */
	private void startWatchdog(int seconds, Path output) {
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(seconds * 1000L);
			} catch (InterruptedException e) {
				return;
			}
			append(output, "{\"timeout\":true}"); //$NON-NLS-1$
			Runtime.getRuntime().halt(3);
		}, "incline-selftest-watchdog"); //$NON-NLS-1$
		watchdog.setDaemon(true);
		watchdog.start();
	}

	private static synchronized void append(Path output, String line) {
		try {
			Files.write(output, (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, //$NON-NLS-1$
					StandardOpenOption.APPEND);
		} catch (Exception e) {
			Activator.logError("自己テストの結果を書けませんでした", e);
		}
	}

	private static String stackTrace(Throwable t) {
		StringWriter writer = new StringWriter();
		t.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}
}
