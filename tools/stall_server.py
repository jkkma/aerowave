"""A radio station that answers and then says nothing.

    python tools/stall_server.py [port]

Serves a valid HTTP 200 with `Content-Type: audio/mpeg` and then holds the
connection open without sending a byte of audio. This is the failure the ring
watchdog exists for and the one `audio.paused` could not see: the element is
not paused, no `error` fires, and nothing ever plays. Point an alarm at
http://127.0.0.1:8811/stall and it must fall back to the backup folder.
"""

import socket
import sys
import threading

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8811

HEADERS = (
    b"HTTP/1.1 200 OK\r\n"
    b"Content-Type: audio/mpeg\r\n"
    b"icy-name:Stall Test\r\n"
    b"Cache-Control: no-cache\r\n"
    b"Connection: keep-alive\r\n"
    b"\r\n"
)


def handle(conn, addr):
    try:
        conn.settimeout(10)
        try:
            conn.recv(4096)
        except OSError:
            pass
        conn.sendall(HEADERS)
        print(f"  stalled {addr[0]}:{addr[1]}", flush=True)
        # Hold it open, sending nothing, until the client gives up.
        while True:
            try:
                if not conn.recv(1):
                    break
            except socket.timeout:
                continue
            except OSError:
                break
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(16)
    print(f"stalling on http://127.0.0.1:{PORT}/stall", flush=True)
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
