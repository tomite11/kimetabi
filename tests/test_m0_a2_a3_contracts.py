import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
OPENAPI_PATH = ROOT / "openapi" / "openapi.json"
SCHEMA_PATH = ROOT / "doc" / "DATABASE_SCHEMA.sql"
SPEC_PATH = ROOT / "doc" / "SPEC.md"


class DatabaseSchemaProposalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = SCHEMA_PATH.read_text(encoding="utf-8")

    def test_contains_m0_a2_required_tables(self):
        required_tables = {
            "trip",
            "trip_member",
            "invite_token",
            "recovery_token",
            "slot",
            "candidate",
            "candidate_tag",
            "candidate_vote",
            "plan_item",
            "expense",
            "expense_receipt",
            "expense_share",
            "settlement",
            "settlement_expense",
            "settlement_share",
            "settlement_transfer",
            "settlement_source_transfer",
            "idempotency_request",
            "audit_event",
            "outbox_event",
        }
        actual_tables = set(
            re.findall(r"CREATE TABLE ([a-z_]+)", self.schema)
        )
        self.assertTrue(
            required_tables.issubset(actual_tables),
            required_tables - actual_tables,
        )

    def test_persists_reproducible_money_and_settlement_snapshots(self):
        for fragment in (
            "final_amount BIGINT",
            "expense_version BIGINT NOT NULL",
            "source_transfer_version BIGINT NOT NULL",
            "base_amount = amount",
            "currency = 'JPY'",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, self.schema)

    def test_has_trip_scoped_foreign_keys_and_operational_indexes(self):
        self.assertGreaterEqual(
            self.schema.count("FOREIGN KEY (") +
            self.schema.count("FOREIGN KEY\n"),
            20,
        )
        self.assertGreaterEqual(self.schema.count(", trip_id)"), 15)
        for index in (
            "uq_trip_active_owner",
            "ix_expense_trip_page",
            "ix_audit_trip_time",
            "ix_outbox_unpublished",
        ):
            with self.subTest(index=index):
                self.assertIn(index, self.schema)

    def test_stores_hashes_not_plaintext_tokens_or_upload_urls(self):
        self.assertIn("token_hash CHAR(64)", self.schema)
        self.assertNotRegex(self.schema, r"\n\s+token\s+")
        self.assertNotIn("upload_url", self.schema)


class OpenApiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.api = json.loads(OPENAPI_PATH.read_text(encoding="utf-8"))
        cls.schemas = cls.api["components"]["schemas"]

    def test_is_openapi_31_and_has_business_routes(self):
        self.assertEqual("3.1.0", self.api["openapi"])
        required_paths = {
            "/api/trips",
            "/api/trips/{tripId}",
            "/api/trips/{tripId}/invitations",
            "/api/trips/{tripId}/invitations/{invitationId}",
            "/api/invitations/accept",
            "/api/trips/{tripId}/members/{memberId}/recovery-links",
            "/api/recoveries/accept",
            "/api/trips/{tripId}/slots/{slotId}",
            "/api/trips/{tripId}/slots/order",
            "/api/trips/{tripId}/slots/{slotId}/split",
            "/api/trips/{tripId}/slots/{slotId}/candidates",
            "/api/trips/{tripId}/candidates/{candidateId}/vote",
            "/api/trips/{tripId}/slots/{slotId}/adoption",
            "/api/trips/{tripId}/expenses",
            "/api/trips/{tripId}/expenses/{expenseId}",
            "/api/trips/{tripId}/expenses/{expenseId}/receipts/{receiptId}/completion",
            "/api/trips/{tripId}/settlements",
            "/api/trips/{tripId}/settlements/{settlementId}/confirmation",
            "/internal/tasks/candidates/{candidateId}/metadata",
            "/internal/outbox/dispatch",
        }
        self.assertTrue(
            required_paths.issubset(self.api["paths"]),
            required_paths - self.api["paths"].keys(),
        )

    def test_all_local_references_resolve(self):
        def visit(value):
            if isinstance(value, dict):
                reference = value.get("$ref")
                if reference:
                    self.assertTrue(reference.startswith("#/"))
                    target = self.api
                    for part in reference[2:].split("/"):
                        target = target[part.replace("~1", "/").replace("~0", "~")]
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)

        visit(self.api)

    def test_write_requests_are_allowlisted_and_versioned(self):
        request_schema_names = {
            name
            for name in self.schemas
            if name.endswith("Request")
        }
        for name in request_schema_names:
            with self.subTest(schema=name):
                self.assertFalse(
                    self.schemas[name].get("additionalProperties", True)
                )

        versioned = {
            "UpdateTripRequest",
            "UpdateSlotRequest",
            "SplitSlotRequest",
            "UpdateCandidateRequest",
            "UpdateExpenseRequest",
            "VersionRequest",
            "UpdateTransferRequest",
        }
        for name in versioned:
            with self.subTest(schema=name):
                self.assertIn("version", self.schemas[name]["required"])

    def test_mutations_document_problem_details(self):
        methods = {"post", "put", "patch", "delete"}
        for path, path_item in self.api["paths"].items():
            for method in methods.intersection(path_item):
                operation = path_item[method]
                responses = operation["responses"]
                with self.subTest(path=path, method=method):
                    self.assertTrue(
                        {"400", "409", "422"}.intersection(responses),
                        "mutation must document a client/domain error",
                    )
                    for response in responses.values():
                        if "$ref" in response:
                            self.assertTrue(
                                response["$ref"].startswith(
                                    "#/components/responses/"
                                )
                            )

    def test_security_and_conflict_contracts_are_explicit(self):
        self.assertEqual(
            [{"firebaseBearer": []}],
            self.api["security"],
        )
        conflict = self.schemas["ConflictProblem"]
        serialized = json.dumps(conflict)
        self.assertIn("currentVersion", serialized)
        self.assertIn('"current"', serialized)
        self.assertEqual(
            100,
            self.api["components"]["parameters"]["PageSize"]["schema"]["maximum"],
        )
        self.assertIn("tasksServiceOidc", self.api["components"]["securitySchemes"])
        self.assertIn(
            "schedulerServiceOidc",
            self.api["components"]["securitySchemes"],
        )
        self.assertIn("TripEvent", self.schemas)

    def test_anonymous_votes_never_expose_other_voters(self):
        description = self.schemas["VoteView"]["properties"]["namedVotes"][
            "description"
        ]
        self.assertIn("Never present for ANONYMOUS", description)
        self.assertIn("including for OWNER and ORGANIZER", description)


class SpecificationDecisionTest(unittest.TestCase):
    def test_spec_records_schema_and_api_decisions(self):
        spec = SPEC_PATH.read_text(encoding="utf-8")
        for decision in (
            "### 5.7 MVP実スキーマの設計判断",
            "`expense_share.final_amount`",
            "`settlement_source_transfer`",
            "追記専用の共通 `audit_event`",
            "`ANONYMOUS` ではOWNER/ORGANIZERを含む全利用者",
            "`openapi/openapi.json`",
        ):
            with self.subTest(decision=decision):
                self.assertIn(decision, spec)


if __name__ == "__main__":
    unittest.main()
