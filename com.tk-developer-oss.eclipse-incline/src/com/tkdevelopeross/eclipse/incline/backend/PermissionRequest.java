package com.tkdevelopeross.eclipse.incline.backend;

import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonObject;

/**
 * ツール実行の許可要求。allow() / deny() はどちらか一度だけ有効。
 */
public final class PermissionRequest {

	public interface Responder {
		void respond(boolean allow, String denyMessage);
	}

	private final String toolName;
	private final String description;
	private final String toolUseId;
	private final JsonObject input;
	private final Responder responder;
	private final AtomicBoolean answered = new AtomicBoolean();

	public PermissionRequest(String toolName, String description, String toolUseId, JsonObject input,
			Responder responder) {
		this.toolName = toolName;
		this.description = description;
		this.toolUseId = toolUseId;
		this.input = input;
		this.responder = responder;
	}

	public String getToolName() {
		return toolName;
	}

	public String getDescription() {
		return description;
	}

	public String getToolUseId() {
		return toolUseId;
	}

	public JsonObject getInput() {
		return input;
	}

	public boolean isAnswered() {
		return answered.get();
	}

	public void allow() {
		if (answered.compareAndSet(false, true)) {
			responder.respond(true, null);
		}
	}

	public void deny(String message) {
		if (answered.compareAndSet(false, true)) {
			responder.respond(false, message);
		}
	}
}
