# TODO

## Faked packets can make the server flood someone else's address

Anyone can send the server a UDP packet with a faked sender IP and a made-up client id. The server then streams up to max-rate (10m by default) to that IP for client-timeout (60 s), and one such packet a minute keeps it going. Many client ids multiply this, and each one also gets its own sender thread and metrics on the server. max-rate alone doesn't stop it.

Ways to fix it:

- Sign packets. Client and server share a secret key (`--key`), each client packet carries an HMAC of its header, and the server drops packets that don't match. The signed header also needs a timestamp, so the server can drop old or repeated packets that someone captured and resent. This also keeps strangers off the server.
- Limit what the server sends each client to twice what it receives from that client. No protocol change, and a real client only notices if over half of its upload is lost.
- Token handshake. The server first replies with a random token and streams only after the client sends it back. This blocks faked IPs completely, but adds a round trip and retries.
