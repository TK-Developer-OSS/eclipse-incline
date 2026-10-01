package com.tkdevelopeross.eclipse.incline.ui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.DialogSettings;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tkdevelopeross.eclipse.incline.Activator;
import com.tkdevelopeross.eclipse.incline.backend.PermissionRequest;

/**
 * ツール実行の許可ダイアログ。OK = 許可、CANCEL (Esc / 閉じる含む) = 拒否。
 * Edit / Write は変更前と変更後を比較ビューアで見せ、それ以外はツールの入力をそのまま見せる。
 */
public class PermissionDialog extends Dialog {

	private final PermissionRequest request;
	private final EditPreview preview;
	private CompareConfiguration compareConfiguration;
	private Button alwaysAllowButton;
	private boolean alwaysAllow;

	public PermissionDialog(Shell parentShell, PermissionRequest request) {
		super(parentShell);
		this.request = request;
		this.preview = EditPreview.create(request.getToolName(), request.getInput());
	}

	public boolean isAlwaysAllow() {
		return alwaysAllow;
	}

	@Override
	protected void configureShell(Shell newShell) {
		super.configureShell(newShell);
		newShell.setText("Claude: ツールの実行許可");
		// 許可するかどうかはユーザーが決める。Claude がこのダイアログを読んだり押したりできないようにする
		newShell.setData(Activator.OWN_DIALOG_KEY, Boolean.TRUE);
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	/** 差分つきのダイアログは広げて使うので、大きさと位置を別々に覚えておく。 */
	@Override
	protected IDialogSettings getDialogBoundsSettings() {
		Activator activator = Activator.getDefault();
		if (activator == null) {
			return null;
		}
		return DialogSettings.getOrCreateSection(activator.getDialogSettings(),
				preview != null ? "PermissionDialog.diff" : "PermissionDialog.text"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);

		Label title = new Label(area, SWT.WRAP);
		title.setText("Claude が次のツールを実行しようとしています: " + request.getToolName());
		title.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
		title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		String description = request.getDescription();
		if (description != null && !description.isBlank()) {
			Label desc = new Label(area, SWT.WRAP);
			desc.setText(description);
			desc.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		}

		if (preview == null || !createDiff(area)) {
			createDetailText(area);
		}

		alwaysAllowButton = new Button(area, SWT.CHECK);
		alwaysAllowButton.setText("このセッションでは " + request.getToolName() + " を確認せずに許可する");

		return area;
	}

	private void createDetailText(Composite parent) {
		Text detail = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
		detail.setFont(JFaceResources.getTextFont());
		detail.setText(formatInput(request.getInput()));
		GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
		gd.widthHint = 600;
		gd.heightHint = 260;
		detail.setLayoutData(gd);
	}

	/**
	 * 変更前 (左) と変更後 (右) を比較ビューアで見せる。
	 *
	 * @return 表示できなかったら false。許可ダイアログが開けないと claude が待ち続けるので、そのときは入力をそのまま見せる
	 */
	private boolean createDiff(Composite parent) {
		Composite diffArea = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 0;
		layout.marginHeight = 0;
		diffArea.setLayout(layout);
		GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
		gd.widthHint = 900;
		gd.heightHint = 460;
		diffArea.setLayoutData(gd);
		try {
			if (preview.isPartial()) {
				Label note = new Label(diffArea, SWT.WRAP);
				note.setText("ファイルの現在の内容から変更箇所を特定できなかったので、変更する部分だけを比較しています。");
				note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			}

			compareConfiguration = new CompareConfiguration();
			compareConfiguration.setLeftEditable(false);
			compareConfiguration.setRightEditable(false);
			if (preview.isNewFile()) {
				compareConfiguration.setLeftLabel("(新しいファイル)");
			} else {
				compareConfiguration.setLeftLabel(preview.isPartial() ? "変更前 (該当部分)" : "現在の内容");
			}
			compareConfiguration.setRightLabel(preview.isPartial() ? "変更後 (該当部分)" : "変更後");

			CompareViewerPane pane = new CompareViewerPane(diffArea, SWT.BORDER | SWT.FLAT);
			pane.setText(preview.getFilePath());
			pane.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
			TextMergeViewer viewer = new TextMergeViewer(pane, SWT.NONE, compareConfiguration);
			pane.setContent(viewer.getControl());
			viewer.setInput(new DiffNode(new TextElement(preview.getFilePath(), preview.getBefore()),
					new TextElement(preview.getFilePath(), preview.getAfter())));
			return true;
		} catch (RuntimeException e) {
			Activator.logError("差分を表示できませんでした: " + preview.getFilePath(), e);
			diffArea.dispose();
			disposeCompareConfiguration();
			return false;
		}
	}

	private void disposeCompareConfiguration() {
		if (compareConfiguration != null) {
			compareConfiguration.dispose();
			compareConfiguration = null;
		}
	}

	@Override
	public boolean close() {
		boolean closed = super.close();
		if (closed) {
			disposeCompareConfiguration();
		}
		return closed;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, "許可", true);
		createButton(parent, IDialogConstants.CANCEL_ID, "拒否", false);
	}

	@Override
	protected void okPressed() {
		alwaysAllow = alwaysAllowButton.getSelection();
		super.okPressed();
	}

	/** 文字列はエスケープせずそのまま見せる (Bash のコマンドなどを読みやすくするため)。 */
	static String formatInput(JsonObject input) {
		if (input == null) {
			return ""; //$NON-NLS-1$
		}
		Gson pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, JsonElement> e : input.entrySet()) {
			JsonElement v = e.getValue();
			String value;
			if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
				value = v.getAsString();
			} else {
				value = pretty.toJson(v);
			}
			if (value.indexOf('\n') >= 0) {
				sb.append(e.getKey()).append(":\n").append(value).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				sb.append(e.getKey()).append(": ").append(value).append('\n'); //$NON-NLS-1$
			}
		}
		return sb.toString();
	}

	/** 比較ビューアに文字列を渡すための要素。 */
	private static final class TextElement implements ITypedElement, IEncodedStreamContentAccessor {

		private final String name;
		private final String text;

		TextElement(String name, String text) {
			this.name = name;
			this.text = text;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Image getImage() {
			return null;
		}

		@Override
		public String getType() {
			return TEXT_TYPE;
		}

		@Override
		public InputStream getContents() {
			return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public String getCharset() {
			return StandardCharsets.UTF_8.name();
		}
	}
}
