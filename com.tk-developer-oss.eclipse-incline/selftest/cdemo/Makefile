CC = gcc
CFLAGS = -g -O0 -Wall

hello.exe: main.c
	$(CC) $(CFLAGS) -o $@ main.c

broken: broken.c
	$(CC) $(CFLAGS) -o broken.exe broken.c

clean:
	rm -f hello.exe broken.exe

.PHONY: broken clean
