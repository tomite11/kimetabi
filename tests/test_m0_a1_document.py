from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT = ROOT / "doc" / "DOMAIN_STATE_INVARIANTS.md"
SPEC = ROOT / "doc" / "SPEC.md"


class M0A1DocumentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = DOCUMENT.read_text(encoding="utf-8")
        cls.spec = SPEC.read_text(encoding="utf-8")

    def test_covers_all_assigned_domain_areas(self):
        for heading in (
            "## 旅行とメンバー",
            "## 枠、候補、投票、採択",
            "## 支出と按分",
            "## 精算",
        ):
            with self.subTest(heading=heading):
                self.assertIn(heading, self.document)

    def test_uses_only_states_defined_by_the_spec(self):
        required_states = {
            "PLANNING",
            "TRAVELING",
            "SETTLING",
            "ACTIVE",
            "LEFT",
            "REMOVED",
            "OPEN",
            "REJECTED",
            "TENTATIVE",
            "DECIDED",
            "DECIDE_LOCALLY",
            "SKIPPED",
            "PENDING",
            "PROCESSING",
            "COMPLETED",
            "FAILED_RETRYABLE",
            "FAILED_PERMANENT",
            "DRAFT",
            "CONFIRMED",
            "SUPERSEDED",
            "PAID",
            "YES",
            "ANY",
            "NO",
        }

        documented_tokens = set(
            re.findall(r"\b[A-Z][A-Z_]+\b", self.document)
        )
        for state in required_states:
            with self.subTest(state=state):
                self.assertIn(state, documented_tokens)
                self.assertIn(state, self.spec)

    def test_records_acceptance_critical_invariants(self):
        required_phrases = (
            "expected_member_count",
            "現在値を伴う `409 Conflict`",
            "member ID昇順",
            "全残高の合計は0",
            "`max(0, n - 1)` 件以内",
            "同じ冪等キー",
            "IANAタイムゾーン",
            "同一トランザクション",
        )
        for phrase in required_phrases:
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, self.document)

    def test_does_not_silently_resolve_cross_lane_open_questions(self):
        for open_question in (
            "投票の匿名・記名設定",
            "DRAFT画像アップロード",
            "監査履歴を汎用イベント",
            "Share TargetをMVP",
        ):
            with self.subTest(open_question=open_question):
                self.assertIn(open_question, self.document)


if __name__ == "__main__":
    unittest.main()
