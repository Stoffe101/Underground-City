"""The production-style launch path authenticates players (D-090): the server pack writes online-mode=true, and the login probe tells an
authenticating server from one that accepts any claimed identity. The probe is exercised against a tiny fake server so the unit test needs no Minecraft."""
import socket
import sys
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import make_server_pack as pack  # noqa: E402
import probe_login as probe  # noqa: E402


def properties_of(text: str) -> dict:
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


class ServerPackProperties(unittest.TestCase):
    def test_the_default_is_an_authenticating_whitelisted_server(self):
        props = properties_of(pack.properties(25565, "Underground City", True))
        self.assertEqual(props["online-mode"], "true")
        self.assertEqual(props["enforce-secure-profile"], "true")
        self.assertEqual(props["white-list"], "true")
        self.assertEqual(props["enforce-whitelist"], "true")
        self.assertEqual(props["pvp"], "false", "the project never forces PvP")
        self.assertEqual(props["level-seed"], "5908", "the proven development seed")

    def test_unauthenticated_is_an_explicit_opt_out_for_throwaway_tests(self):
        props = properties_of(pack.properties(25565, "test", False))
        self.assertEqual(props["online-mode"], "false")

    def test_the_cli_default_is_authenticated_and_the_eula_is_left_to_the_operator(self):
        source = (ROOT / "scripts/make_server_pack.py").read_text(encoding="utf-8")
        self.assertIn('properties(args.port, args.motd, not args.unauthenticated)', source)
        self.assertIn('"eula=false', source)
        self.assertIn('action="store_true", help="online-mode=false. ONLY for a throwaway test', source)

    def test_whitelist_commands_name_each_player_once(self):
        self.assertEqual(pack.whitelist_file(["Alice", "Bob"]), "whitelist add Alice\nwhitelist add Bob\n")

    def test_start_scripts_use_the_forge_argument_files_for_each_platform(self):
        self.assertIn("win_args.txt", pack.WINDOWS_ARGS)
        self.assertIn("unix_args.txt", pack.UNIX_ARGS)


class ProtocolEncoding(unittest.TestCase):
    def test_varints_match_the_protocol_examples(self):
        self.assertEqual(probe.varint(0), b"\x00")
        self.assertEqual(probe.varint(127), b"\x7f")
        self.assertEqual(probe.varint(128), b"\x80\x01")
        self.assertEqual(probe.varint(763), b"\xfb\x05", "the 1.20.1 protocol number")

    def test_a_packet_is_length_prefixed(self):
        self.assertEqual(probe.packet(0x00, b"ab"), b"\x03\x00ab")


def fake_server(reply_id: int, reply_payload: bytes):
    """A one-shot server that reads the handshake and login start, then answers with one packet. Returns (port, thread)."""
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)

    def serve():
        connection, _ = listener.accept()
        with connection:
            connection.settimeout(5)
            try:
                connection.recv(4096)   # handshake and login start arrive together or separately; one read is enough for the fake
                connection.sendall(probe.packet(reply_id, reply_payload))
            finally:
                listener.close()

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()
    return listener.getsockname()[1], thread


class LoginProbe(unittest.TestCase):
    def test_an_encryption_request_means_the_server_authenticates(self):
        port, thread = fake_server(0x01, probe.string("") + b"\x01\x02" + b"\x04\x01\x02\x03\x04")
        verdict, detail = probe.probe("127.0.0.1", port, "UcProbe", 5)
        thread.join(5)
        self.assertEqual(verdict, "authenticated", detail)

    def test_anything_else_means_the_claimed_identity_was_accepted(self):
        for packet_id in (0x02, 0x03, 0x04):
            port, thread = fake_server(packet_id, b"\x00")
            verdict, detail = probe.probe("127.0.0.1", port, "UcProbe", 5)
            thread.join(5)
            self.assertEqual(verdict, "unauthenticated", f"packet {packet_id}: {detail}")

    def test_a_disconnect_is_reported_as_refused(self):
        port, thread = fake_server(0x00, probe.string('{"text":"nope"}'))
        verdict, detail = probe.probe("127.0.0.1", port, "UcProbe", 5)
        thread.join(5)
        self.assertEqual(verdict, "refused", detail)
        self.assertIn("nope", detail)

    def test_the_probe_claims_the_uuid_an_unauthenticated_server_would_derive(self):
        # values produced by Java's UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8)), the rule Minecraft uses
        self.assertEqual(str(probe.offline_uuid("Dev")), "380df991-f603-344c-a090-369bad2a924a")
        self.assertEqual(str(probe.offline_uuid("Alice")), "10920508-d5d8-3eed-93d2-92f193afe7d7")
        self.assertEqual(probe.offline_uuid("Dev").version, 3)


if __name__ == "__main__":
    unittest.main()
