public class Main {
	public static void main(String[] args) {
		int unused = 1;
		int total = 0;
		for (int i = 1; i <= 3; i++) {
			total += i;
			System.out.println("step " + i + " total " + total);
		}
		System.out.println("こんにちは demo: " + total);
		if (args.length > 0) {
			System.err.println("arg: " + args[0]);
			System.exit(3);
		}
	}
}
