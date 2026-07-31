import json
import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class FrontendContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.openapi = json.loads((ROOT / "openapi/openapi.json").read_text())
        cls.screen_map = (ROOT / "doc/FRONTEND_SCREEN_MAP.md").read_text()
        cls.state_design = (ROOT / "doc/FRONTEND_STATE_DESIGN.md").read_text()
        cls.scenarios = (ROOT / "doc/E2E_SCENARIOS.md").read_text()

    def resolve(self, reference):
        value = self.openapi
        for part in reference.removeprefix("#/").split("/"):
            value = value[part]
        return value

    def operation_ids(self):
        return {
            operation["operationId"]
            for path in self.openapi["paths"].values()
            for operation in path.values()
            if isinstance(operation, dict) and "operationId" in operation
        }

    def validate(self, value, schema):
        if "$ref" in schema:
            return self.validate(value, self.resolve(schema["$ref"]))
        if "oneOf" in schema:
            self.assertEqual(
                sum(self.is_valid(value, item) for item in schema["oneOf"]), 1
            )
            return
        if "anyOf" in schema:
            self.assertTrue(any(self.is_valid(value, item) for item in schema["anyOf"]))
        if "const" in schema:
            self.assertEqual(value, schema["const"])
        if schema.get("type") == "object":
            self.assertIsInstance(value, dict)
            self.assertTrue(set(schema.get("required", ())) <= set(value))
            if schema.get("additionalProperties") is False:
                self.assertFalse(set(value) - set(schema.get("properties", {})))
            for key, item in value.items():
                if key in schema.get("properties", {}):
                    self.validate(item, schema["properties"][key])
        elif schema.get("type") == "array":
            self.assertIsInstance(value, list)
            self.assertLessEqual(len(value), schema.get("maxItems", len(value)))
            if schema.get("uniqueItems"):
                self.assertEqual(len(value), len({json.dumps(item, sort_keys=True) for item in value}))
            for item in value:
                self.validate(item, schema["items"])
        elif schema.get("type") == "string":
            self.assertIsInstance(value, str)
            self.assertGreaterEqual(len(value), schema.get("minLength", 0))
            self.assertLessEqual(len(value), schema.get("maxLength", len(value)))
            if "enum" in schema:
                self.assertIn(value, schema["enum"])
            if "pattern" in schema:
                self.assertRegex(value, re.compile(schema["pattern"]))
        elif schema.get("type") == "integer":
            self.assertIsInstance(value, int)
            self.assertGreaterEqual(value, schema.get("minimum", value))
            self.assertLessEqual(value, schema.get("maximum", value))
        elif schema.get("type") == "boolean":
            self.assertIsInstance(value, bool)
        elif schema.get("type") == "null":
            self.assertIsNone(value)

    def is_valid(self, value, schema):
        try:
            self.validate(value, schema)
            return True
        except AssertionError:
            return False

    def test_screen_map_covers_canonical_routes_and_empty_states(self):
        routes = [
            "/", "/t/:tripId", "/t/:tripId/plan",
            "/t/:tripId/plan/:slotId", "/t/:tripId/expenses",
            "/t/:tripId/expenses/new", "/t/:tripId/settle",
            "/join/:inviteToken", "/recover/:recoveryToken",
            "/candidates/import",
        ]
        for route in routes:
            self.assertIn(f"`{route}`", self.screen_map)
        for phrase in ["旅行を作る", "URLを貼るか、共有から追加できます", "撮影／金額入力"]:
            self.assertIn(phrase, self.screen_map)

    def test_accessibility_and_offline_boundaries_are_explicit(self):
        for phrase in [
            ":focus-visible", "prefers-reduced-motion", "640px", "NavLink",
            "成功応答を確認した後だけ", "firebaseUid", "Idempotency-Key",
            "Cache Storage", "REST同期を完了してから購読",
        ]:
            self.assertIn(phrase, self.screen_map + self.state_design)

    def test_generated_openapi_types_are_the_only_api_model_contract(self):
        self.assertIn("frontend/src/api/generated/schema.d.ts", self.state_design)
        self.assertIn('components["schemas"]', self.state_design)
        self.assertIn("手書きDTOを作らない", self.state_design)

    def test_examples_reference_operations_and_match_schemas(self):
        fixtures = json.loads(
            (ROOT / "openapi/fixtures/m0-c3-api-examples.json").read_text()
        )
        operation_ids = self.operation_ids()
        for example in fixtures["examples"]:
            self.assertIn(example["operationId"], operation_ids)
            self.validate(example["body"], self.resolve(example["schemaRef"]))

    def test_e2e_contract_has_required_risk_scenarios(self):
        for scenario in [
            "E2E-JOIN-01", "E2E-PLAN-02", "E2E-EXP-02",
            "E2E-SYNC-01", "E2E-CONFLICT-01", "E2E-A11Y-01", "E2E-EMPTY-01",
        ]:
            self.assertIn(scenario, self.scenarios)


if __name__ == "__main__":
    unittest.main()
