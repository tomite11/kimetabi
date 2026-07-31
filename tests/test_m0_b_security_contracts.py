import ipaddress
import json
from pathlib import Path
import re
import unittest
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = ROOT / "doc" / "security" / "url-fetch-policy.json"
VECTORS_PATH = ROOT / "doc" / "security" / "url-fetch-test-vectors.json"


class UrlFetchPolicy:
    def __init__(self, policy):
        self.policy = policy

    @staticmethod
    def _is_ambiguous_numeric_host(host):
        if re.fullmatch(r"\d+", host):
            return True
        if re.fullmatch(r"0[xX][0-9a-fA-F]+", host):
            return True
        labels = host.split(".")
        return (
            len(labels) == 4
            and all(re.fullmatch(r"\d+", label) for label in labels)
            and any(len(label) > 1 and label.startswith("0") for label in labels)
        )

    @staticmethod
    def _is_public_address(raw_address):
        try:
            address = ipaddress.ip_address(raw_address)
        except ValueError:
            return False

        if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
            address = address.ipv4_mapped
        return (
            address.is_global
            and not address.is_multicast
            and not address.is_unspecified
            and not address.is_reserved
            and not address.is_loopback
            and not address.is_link_local
            and not address.is_private
        )

    def allows_url(self, raw_url, resolved_addresses):
        try:
            parsed = urlsplit(raw_url)
            port = parsed.port
        except ValueError:
            return False

        if parsed.scheme not in self.policy["allowedSchemes"]:
            return False
        if not parsed.hostname:
            return False
        if self.policy["rejectUserInfo"] and (
            parsed.username is not None or parsed.password is not None
        ):
            return False

        host = parsed.hostname.rstrip(".").lower()
        if host in self.policy["blockedHostnames"]:
            return False
        if self._is_ambiguous_numeric_host(host):
            return False

        effective_port = port
        if effective_port is None:
            effective_port = 443 if parsed.scheme == "https" else 80
        if effective_port not in self.policy["allowedPorts"]:
            return False

        if not resolved_addresses:
            return False
        return all(
            self._is_public_address(address)
            for address in resolved_addresses
        )

    def allows_redirect_case(self, case):
        redirect_count = case.get(
            "redirectCount",
            max(0, len(case.get("hops", [])) - 1),
        )
        if redirect_count > self.policy["maxRedirects"]:
            return False
        return all(
            self.allows_url(hop["url"], hop["resolvedAddresses"])
            for hop in case.get("hops", [])
        )

    def allows_connection(self, validated_addresses, connected_address):
        try:
            connected = ipaddress.ip_address(connected_address)
            validated = {
                ipaddress.ip_address(address)
                for address in validated_addresses
            }
        except ValueError:
            return False
        return connected in validated and self._is_public_address(
            connected_address
        )

    def allows_limits(self, case):
        content_length = case.get("contentLength")
        return (
            case["connectElapsedMs"] <= self.policy["connectTimeoutMs"]
            and case["totalElapsedMs"] <= self.policy["totalTimeoutMs"]
            and (
                content_length is None
                or content_length <= self.policy["maxBodyBytes"]
            )
            and case["bodyBytes"] <= self.policy["maxBodyBytes"]
        )


class UrlFetchSecurityVectorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
        cls.raw_policy = policy
        cls.policy = UrlFetchPolicy(policy)
        cls.vectors = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))

    def test_initial_url_ipv4_ipv6_scheme_and_port_vectors(self):
        for case in self.vectors["urlCases"]:
            with self.subTest(case=case["name"]):
                self.assertEqual(
                    case["allowed"],
                    self.policy.allows_url(
                        case["url"],
                        case["resolvedAddresses"],
                    ),
                )

    def test_every_redirect_hop_and_redirect_limit(self):
        for case in self.vectors["redirectCases"]:
            with self.subTest(case=case["name"]):
                self.assertEqual(
                    case["allowed"],
                    self.policy.allows_redirect_case(case),
                )

    def test_connection_is_pinned_to_validated_dns_answer(self):
        for case in self.vectors["connectionCases"]:
            with self.subTest(case=case["name"]):
                self.assertEqual(
                    case["allowed"],
                    self.policy.allows_connection(
                        case["validatedAddresses"],
                        case["connectedAddress"],
                    ),
                )

    def test_connect_total_timeout_and_body_size_vectors(self):
        for case in self.vectors["limitCases"]:
            with self.subTest(case=case["name"]):
                self.assertEqual(
                    case["allowed"],
                    self.policy.allows_limits(case),
                )

    def test_policy_uses_specified_limits(self):
        self.assertEqual(3000, self.raw_policy["connectTimeoutMs"])
        self.assertEqual(5000, self.raw_policy["totalTimeoutMs"])
        self.assertEqual(2097152, self.raw_policy["maxBodyBytes"])
        self.assertEqual(5, self.raw_policy["maxRedirects"])
        self.assertEqual([80, 443], self.raw_policy["allowedPorts"])


class SecurityDesignDocumentTest(unittest.TestCase):
    def test_m0_b_deliverables_cover_required_boundaries(self):
        boundaries = (ROOT / "doc" / "SECURITY_BOUNDARIES.md").read_text(
            encoding="utf-8"
        )
        async_contracts = (
            ROOT / "doc" / "ASYNC_EVENT_CONTRACTS.md"
        ).read_text(encoding="utf-8")
        checklist = (ROOT / "doc" / "SECURITY_CHECKLIST.md").read_text(
            encoding="utf-8"
        )

        for term in (
            "Firebase Admin SDK",
            "ACTIVE",
            "SUBSCRIBE",
            "128 bit",
            "rate limit",
        ):
            with self.subTest(document="boundaries", term=term):
                self.assertIn(term, boundaries)

        for term in (
            "Outbox",
            "Cloud Tasks",
            "event ID",
            "trip revision",
            "Cloud Storage",
            "少なくとも1回",
        ):
            with self.subTest(document="async", term=term):
                self.assertIn(term, async_contracts)

        for term in (
            "OIDC",
            "SSRF",
            "bearer token",
            "signed URL",
            "未決事項",
        ):
            with self.subTest(document="checklist", term=term):
                self.assertIn(term, checklist)

    def test_event_names_are_unique(self):
        text = (ROOT / "doc" / "ASYNC_EVENT_CONTRACTS.md").read_text(
            encoding="utf-8"
        )
        event_names = re.findall(r"\| `([A-Z][A-Z_]+)` \|", text)
        self.assertGreaterEqual(len(event_names), 10)
        self.assertEqual(len(event_names), len(set(event_names)))

        api = json.loads(
            (ROOT / "openapi" / "openapi.json").read_text(encoding="utf-8")
        )
        api_event_names = api["components"]["schemas"]["TripEventType"]["enum"]
        self.assertEqual(set(event_names), set(api_event_names))


if __name__ == "__main__":
    unittest.main()
