import json
import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
INFRA = ROOT / "infrastructure"


class M8BInfrastructureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (INFRA / "main.tf").read_text(encoding="utf-8")
        cls.variables = (INFRA / "variables.tf").read_text(encoding="utf-8")

    def test_required_platform_resources_are_managed(self):
        for resource in (
            "google_artifact_registry_repository",
            "google_firebase_hosting_site",
            "google_cloud_run_v2_service",
            "google_sql_database_instance",
            "google_cloud_tasks_queue",
            "google_storage_bucket",
            "google_secret_manager_secret",
            "google_cloud_scheduler_job",
            "google_monitoring_alert_policy",
        ):
            self.assertIn(f'resource "{resource}"', self.main)

    def test_cloud_run_release_limits_are_fixed(self):
        self.assertGreaterEqual(self.main.count("min_instance_count = 0"), 2)
        self.assertGreaterEqual(self.main.count("max_instance_count = 1"), 2)
        self.assertIn('timeout         = "3600s"', self.main)
        self.assertIn('cpu    = "1"', self.main)
        self.assertIn('memory = "1Gi"', self.main)

    def test_database_backup_pitr_and_both_deletion_guards_are_enabled(self):
        self.assertIn("deletion_protection = true", self.main)
        self.assertIn("deletion_protection_enabled = true", self.main)
        self.assertIn("enabled                        = true", self.main)
        self.assertIn("point_in_time_recovery_enabled = true", self.main)
        self.assertIn("retained_backups = var.retained_backups", self.main)
        self.assertIn(
            "transaction_log_retention_days = var.transaction_log_retention_days",
            self.main,
        )
        self.assertIn('default     = "db-g1-small"', self.variables)
        self.assertIn("default     = 14", self.variables)
        self.assertIn("default     = 7", self.variables)

    def test_storage_and_identities_follow_least_privilege_boundaries(self):
        self.assertIn('public_access_prevention    = "enforced"', self.main)
        self.assertIn('uniform_bucket_level_access = true', self.main)
        self.assertIn('role   = "roles/storage.objectUser"', self.main)
        self.assertNotIn("roles/storage.admin", self.main)
        self.assertIn('account_id   = "${local.prefix}-tasks"', self.main)
        self.assertIn('account_id   = "${local.prefix}-scheduler"', self.main)

    def test_security_inputs_reject_wildcard_cors_and_mutable_images(self):
        self.assertIn('!strcontains(origin, "*")', self.variables)
        self.assertIn('^https://[A-Za-z0-9.-]+(?::[0-9]+)?$', self.variables)
        self.assertIn('@sha256:[0-9a-f]{64}$', self.variables)
        self.assertNotRegex(self.main, r'(?i)(password|secret)\s*=\s*"[^"$]+"')

    def test_closed_beta_recovery_and_alert_defaults_are_fixed(self):
        self.assertIn('default     = "18:00"', self.variables)
        self.assertIn('default     = "*/5 * * * *"', self.variables)
        self.assertIn("default     = 4", self.variables)
        self.assertIn("default     = 0", self.variables)
        self.assertIn("default     = 0.8", self.variables)
        self.assertIn(
            "length(distinct(var.alert_notification_channel_names)) >= 2",
            self.variables,
        )

    def test_cloud_run_explicitly_trusts_only_its_google_proxy_boundary(self):
        self.assertRegex(
            self.main,
            r'name\s*=\s*"TRUST_GOOGLE_FORWARDED_FOR"\s+value\s*=\s*"true"',
        )

    def test_hosting_has_spa_fallback_and_security_headers(self):
        config = json.loads((ROOT / "firebase.json").read_text(encoding="utf-8"))
        hosting = config["hosting"]
        self.assertEqual(hosting["rewrites"], [{"source": "**", "destination": "/index.html"}])
        headers = {
            entry["key"]: entry["value"]
            for rule in hosting["headers"]
            if rule["source"] == "**"
            for entry in rule["headers"]
        }
        self.assertIn("Content-Security-Policy", headers)
        self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")

    def test_backend_container_drops_root(self):
        dockerfile = (ROOT / "backend" / "Dockerfile").read_text(encoding="utf-8")
        self.assertIn("USER 10001:10001", dockerfile)
        self.assertNotIn("COPY . .", dockerfile)


if __name__ == "__main__":
    unittest.main()
