package com.tkdevelopeross.eclipse.incline.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 画面の部品を、表示されている文字で探すための照合。
 * Claude が渡すラベルは、装飾 (ニーモニック、ショートカット表示、件数など) が付いた実際の表示と
 * 完全には一致しないことが多いので、完全一致 → 前方一致 → 部分一致の順に緩めて探す。
 */
final class Labels {

	private static final int MAX_LISTED = 40;

	private Labels() {
	}

	/** ニーモニックの &amp; と、メニューのショートカット表示 (タブ以降) を取り除く。 */
	static String normalize(String label) {
		if (label == null) {
			return ""; //$NON-NLS-1$
		}
		int tab = label.indexOf('\t');
		String text = tab >= 0 ? label.substring(0, tab) : label;
		return text.replace("&", "").replace('\n', ' ').replace('\r', ' ').trim(); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * @return 一番厳しい照合で見つかった候補すべて (完全一致があればそれだけ、なければ前方一致、それもなければ部分一致)。
	 *         大文字と小文字は区別しない
	 */
	static <T> List<T> matches(List<T> candidates, Function<T, String> label, String wanted) {
		String want = normalize(wanted).toLowerCase(Locale.ROOT);
		if (want.isEmpty()) {
			throw new IllegalArgumentException("The label to look for is empty."); //$NON-NLS-1$
		}
		List<T> exact = new ArrayList<>();
		List<T> prefix = new ArrayList<>();
		List<T> partial = new ArrayList<>();
		for (T candidate : candidates) {
			String have = normalize(label.apply(candidate)).toLowerCase(Locale.ROOT);
			if (have.equals(want)) {
				exact.add(candidate);
			} else if (have.startsWith(want)) {
				prefix.add(candidate);
			} else if (have.contains(want)) {
				partial.add(candidate);
			}
		}
		if (!exact.isEmpty()) {
			return exact;
		}
		return !prefix.isEmpty() ? prefix : partial;
	}

	/**
	 * @return 見つからなければ null。同じラベルのものが複数あれば最初のもの
	 * @throws IllegalStateException ラベルの違う候補が複数あって、1 つに決められない
	 */
	static <T> T single(List<T> candidates, Function<T, String> label, String wanted) {
		List<T> found = matches(candidates, label, wanted);
		if (found.isEmpty()) {
			return null;
		}
		String first = normalize(label.apply(found.get(0)));
		for (T other : found) {
			if (!normalize(label.apply(other)).equals(first)) {
				throw new IllegalStateException(quote(wanted) + " matches several: " + list(found, label) //$NON-NLS-1$
						+ ". Use the full label."); //$NON-NLS-1$
			}
		}
		return found.get(0);
	}

	/** @return "a", "b", "c" のような並び。多すぎるときは途中まで */
	static <T> String list(List<T> items, Function<T, String> label) {
		if (items.isEmpty()) {
			return "(none)"; //$NON-NLS-1$
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < items.size(); i++) {
			if (i == MAX_LISTED) {
				sb.append(", ... (").append(items.size() - i).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
				break;
			}
			sb.append(i > 0 ? ", " : "").append(quote(normalize(label.apply(items.get(i))))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return sb.toString();
	}

	static String quote(String text) {
		return '"' + text + '"';
	}
}
