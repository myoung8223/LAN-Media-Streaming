# Makefile — LAN Media Receiver (Linux)
#
# Builds two binaries:
#   lanmedia-receiver           the streaming daemon (SDL2 + FFmpeg + libopus + OpenSSL)
#   lanmedia-receiver-settings  the GTK3 settings editor
#
# Requires pkg-config and the -dev packages listed in README.md.

CC      ?= cc
CFLAGS  ?= -O2 -Wall -Wextra
PREFIX  ?= /usr/local
DESTDIR ?=

PKG_DAEMON = sdl2 libavcodec libavutil libswscale opus openssl
PKG_GUI    = gtk+-3.0

DAEMON_CFLAGS := $(shell pkg-config --cflags $(PKG_DAEMON))
DAEMON_LIBS   := $(shell pkg-config --libs $(PKG_DAEMON)) -lpthread -lm
GUI_CFLAGS    := $(shell pkg-config --cflags $(PKG_GUI))
GUI_LIBS      := $(shell pkg-config --libs $(PKG_GUI))

all: lanmedia-receiver lanmedia-receiver-settings

config.o: src/config.c src/common.h
	$(CC) $(CFLAGS) -c src/config.c -o config.o

receiver.o: src/receiver.c src/common.h
	$(CC) $(CFLAGS) $(DAEMON_CFLAGS) -c src/receiver.c -o receiver.o

settings_gui.o: src/settings_gui.c src/common.h
	$(CC) $(CFLAGS) $(GUI_CFLAGS) -c src/settings_gui.c -o settings_gui.o

lanmedia-receiver: receiver.o config.o
	$(CC) receiver.o config.o -o $@ $(DAEMON_LIBS)

lanmedia-receiver-settings: settings_gui.o config.o
	$(CC) settings_gui.o config.o -o $@ $(GUI_LIBS)

install: all
	install -Dm755 lanmedia-receiver          $(DESTDIR)$(PREFIX)/bin/lanmedia-receiver
	install -Dm755 lanmedia-receiver-settings $(DESTDIR)$(PREFIX)/bin/lanmedia-receiver-settings
	install -Dm644 desktop/lanmedia-receiver-settings.desktop \
	    $(DESTDIR)$(PREFIX)/share/applications/lanmedia-receiver-settings.desktop

clean:
	rm -f *.o lanmedia-receiver lanmedia-receiver-settings

.PHONY: all install clean
