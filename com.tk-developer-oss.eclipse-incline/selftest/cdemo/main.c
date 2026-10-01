#include <stdio.h>

static int sum_to(int n)
{
	int total = 0;
	for (int i = 1; i <= n; i++) {
		total += i;
	}
	return total;
}

int main(int argc, char **argv)
{
	int result = sum_to(10);
	printf("sum 1..10 = %d\n", result);
	printf("args: %d\n", argc - 1);
	return argc > 1 ? 3 : 0;
}
