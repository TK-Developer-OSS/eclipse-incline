import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/** 自己テストの結果を、手順ごとに「何をしたか」と「返ってきた文章」で表示する。 */
public class Show {
	public static void main(String[] a) throws Exception {
		int max = a.length > 1 ? Integer.parseInt(a[1]) : 1500;
		for (String line : Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8)) {
			JsonObject o = new Gson().fromJson(line, JsonObject.class);
			if (!o.has("n")) {
				System.out.println("== " + line);
				continue;
			}
			JsonObject step = o.getAsJsonObject("step");
			String head = step.has("tool") ? step.get("tool").getAsString() + " " + (step.has("args") ? step.get("args") : "")
					: step.toString();
			if (head.length() > 170) {
				head = head.substring(0, 170) + "...";
			}
			System.out.println("[" + o.get("n").getAsInt() + "] " + head + "  (" + o.get("ms").getAsLong() + "ms"
					+ (o.has("auto") ? ", auto=" + o.get("auto").getAsBoolean() : "")
					+ (o.has("isError") && o.get("isError").getAsBoolean() ? ", ERROR" : "") + ")");
			String text = o.has("failed") ? "FAILED: " + o.get("failed").getAsString()
					: o.has("rpcError") ? "RPC ERROR " + o.get("rpcError") : o.has("text") ? o.get("text").getAsString() : "";
			if (text.length() > max) {
				text = text.substring(0, max) + "\n   ...(" + (text.length() - max) + " more chars)";
			}
			for (String l : text.split("\n")) {
				System.out.println("    " + l);
			}
		}
	}
}
