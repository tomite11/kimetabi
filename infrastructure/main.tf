locals {
  prefix = "kimetabi-${var.environment}"
  labels = {
    application = "kimetabi"
    environment = var.environment
    managed_by  = "terraform"
  }
  required_services = toset([
    "artifactregistry.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "cloudscheduler.googleapis.com",
    "cloudtasks.googleapis.com",
    "firebase.googleapis.com",
    "firebasehosting.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "sqladmin.googleapis.com",
    "storage.googleapis.com"
  ])
}

resource "google_firebase_project" "application" {
  provider = google-beta
  project  = var.project_id

  depends_on = [google_project_service.required]
}

resource "google_firebase_hosting_site" "application" {
  provider = google-beta
  project  = var.project_id
  site_id  = var.hosting_site_id

  depends_on = [google_firebase_project.application]
}

data "google_project" "current" {
  project_id = var.project_id
}

resource "google_project_service" "required" {
  for_each           = local.required_services
  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "backend" {
  project       = var.project_id
  location      = var.region
  repository_id = "${local.prefix}-backend"
  format        = "DOCKER"
  description   = "Immutable backend images for ${local.prefix}"
  labels        = local.labels

  depends_on = [google_project_service.required]
}

resource "google_service_account" "runtime" {
  project      = var.project_id
  account_id   = "${local.prefix}-runtime"
  display_name = "${local.prefix} Cloud Run runtime"
}

resource "google_service_account" "tasks" {
  project      = var.project_id
  account_id   = "${local.prefix}-tasks"
  display_name = "${local.prefix} Cloud Tasks OIDC caller"
}

resource "google_service_account" "scheduler" {
  project      = var.project_id
  account_id   = "${local.prefix}-scheduler"
  display_name = "${local.prefix} Cloud Scheduler OIDC caller"
}

resource "google_project_iam_member" "runtime_roles" {
  for_each = toset([
    "roles/cloudsql.client",
    "roles/cloudtasks.enqueuer",
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter"
  ])
  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_service_account_iam_member" "runtime_can_use_tasks_identity" {
  service_account_id = google_service_account.tasks.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_service_account_iam_member" "runtime_can_sign_upload_urls" {
  service_account_id = google_service_account.runtime.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_service_account_iam_member" "tasks_service_agent_token_creator" {
  service_account_id = google_service_account.tasks.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-cloudtasks.iam.gserviceaccount.com"
}

resource "google_service_account_iam_member" "scheduler_service_agent_token_creator" {
  service_account_id = google_service_account.scheduler.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-cloudscheduler.iam.gserviceaccount.com"
}

resource "google_sql_database_instance" "postgres" {
  project             = var.project_id
  name                = "${local.prefix}-postgres"
  region              = var.region
  database_version    = "POSTGRES_17"
  deletion_protection = true

  settings {
    tier                        = var.cloud_sql_tier
    availability_type           = "ZONAL"
    deletion_protection_enabled = true
    disk_autoresize             = true
    disk_type                   = "PD_SSD"
    edition                     = "ENTERPRISE"
    user_labels                 = local.labels

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = var.backup_start_time
      transaction_log_retention_days = var.transaction_log_retention_days
      backup_retention_settings {
        retained_backups = var.retained_backups
        retention_unit   = "COUNT"
      }
    }

    ip_configuration {
      ipv4_enabled = true
      ssl_mode     = "ENCRYPTED_ONLY"
    }
  }

  depends_on = [google_project_service.required]
}

resource "google_sql_database" "application" {
  project  = var.project_id
  name     = var.database_name
  instance = google_sql_database_instance.postgres.name
}

resource "google_storage_bucket" "receipts" {
  project                     = var.project_id
  name                        = "${var.project_id}-${local.prefix}-receipts"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  labels                      = local.labels
}

resource "google_storage_bucket_iam_member" "runtime_receipts" {
  bucket = google_storage_bucket.receipts.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_cloud_tasks_queue" "metadata" {
  project  = var.project_id
  name     = "${local.prefix}-metadata"
  location = var.region

  retry_config {
    max_attempts       = 3
    min_backoff        = "60s"
    max_backoff        = "600s"
    max_doublings      = 4
    max_retry_duration = "1800s"
  }

  rate_limits {
    max_concurrent_dispatches = 2
    max_dispatches_per_second = 2
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret" "database" {
  for_each  = toset(["url", "username", "password"])
  project   = var.project_id
  secret_id = "${local.prefix}-database-${each.key}"
  labels    = local.labels

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_iam_member" "runtime_database" {
  for_each  = google_secret_manager_secret.database
  project   = var.project_id
  secret_id = each.value.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_cloud_run_v2_service" "api" {
  project             = var.project_id
  name                = "${local.prefix}-api"
  location            = var.region
  deletion_protection = true
  ingress             = "INGRESS_TRAFFIC_ALL"
  custom_audiences = [
    "${var.backend_public_base_url}/internal/tasks",
    "${var.backend_public_base_url}/internal/scheduler"
  ]
  labels = local.labels

  scaling {
    min_instance_count = 0
    max_instance_count = 1
  }

  template {
    service_account = google_service_account.runtime.email
    timeout         = "3600s"

    scaling {
      min_instance_count = 0
      max_instance_count = 1
    }

    containers {
      image = var.backend_image

      resources {
        cpu_idle = true
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
      }

      ports {
        container_port = 8080
      }

      startup_probe {
        initial_delay_seconds = 10
        timeout_seconds       = 3
        period_seconds        = 5
        failure_threshold     = 24
        http_get {
          path = "/actuator/health/liveness"
          port = 8080
        }
      }

      env {
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name  = "FIREBASE_PROJECT_ID"
        value = var.project_id
      }
      env {
        name  = "CORS_ALLOWED_ORIGINS"
        value = join(",", var.cors_allowed_origins)
      }
      env {
        name  = "TRUST_GOOGLE_FORWARDED_FOR"
        value = "true"
      }
      env {
        name  = "CLOUD_TASKS_LOCATION"
        value = google_cloud_tasks_queue.metadata.location
      }
      env {
        name  = "CLOUD_TASKS_METADATA_QUEUE"
        value = google_cloud_tasks_queue.metadata.name
      }
      env {
        name  = "BACKEND_BASE_URL"
        value = var.backend_public_base_url
      }
      env {
        name  = "TASKS_SERVICE_ACCOUNT_EMAIL"
        value = google_service_account.tasks.email
      }
      env {
        name  = "TASKS_OIDC_AUDIENCE"
        value = "${var.backend_public_base_url}/internal/tasks"
      }
      env {
        name  = "SCHEDULER_SERVICE_ACCOUNT_EMAIL"
        value = google_service_account.scheduler.email
      }
      env {
        name  = "SCHEDULER_OIDC_AUDIENCE"
        value = "${var.backend_public_base_url}/internal/scheduler"
      }
      env {
        name  = "RECEIPT_STORAGE_BUCKET"
        value = google_storage_bucket.receipts.name
      }
      env {
        name = "DATABASE_URL"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.database["url"].secret_id
            version = "latest"
          }
        }
      }
      env {
        name = "DATABASE_USERNAME"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.database["username"].secret_id
            version = "latest"
          }
        }
      }
      env {
        name = "DATABASE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.database["password"].secret_id
            version = "latest"
          }
        }
      }

    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_iam_member.runtime_database
  ]
}

resource "google_cloud_run_v2_service_iam_member" "public_api" {
  project  = var.project_id
  location = google_cloud_run_v2_service.api.location
  name     = google_cloud_run_v2_service.api.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_service_iam_member" "internal_callers" {
  for_each = {
    tasks     = google_service_account.tasks.email
    scheduler = google_service_account.scheduler.email
  }
  project  = var.project_id
  location = google_cloud_run_v2_service.api.location
  name     = google_cloud_run_v2_service.api.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${each.value}"
}

resource "google_cloud_scheduler_job" "outbox_recovery" {
  project          = var.project_id
  region           = var.region
  name             = "${local.prefix}-outbox-recovery"
  description      = "Recover unpublished Outbox events"
  schedule         = var.outbox_recovery_schedule
  time_zone        = "UTC"
  attempt_deadline = "60s"

  retry_config {
    retry_count          = 3
    min_backoff_duration = "30s"
    max_backoff_duration = "300s"
    max_doublings        = 3
  }

  http_target {
    http_method = "POST"
    uri         = "${google_cloud_run_v2_service.api.uri}/internal/outbox/dispatch"
    headers     = { "Content-Type" = "application/json" }
    body        = base64encode(jsonencode({ limit = 100 }))
    oidc_token {
      service_account_email = google_service_account.scheduler.email
      audience              = "${var.backend_public_base_url}/internal/scheduler"
    }
  }
}

resource "google_cloud_scheduler_job" "receipt_cleanup" {
  project          = var.project_id
  region           = var.region
  name             = "${local.prefix}-receipt-cleanup"
  description      = "Delete expired orphan receipt objects"
  schedule         = "17,47 * * * *"
  time_zone        = "UTC"
  attempt_deadline = "60s"

  http_target {
    http_method = "POST"
    uri         = "${google_cloud_run_v2_service.api.uri}/internal/receipts/orphans/cleanup"
    oidc_token {
      service_account_email = google_service_account.scheduler.email
      audience              = "${var.backend_public_base_url}/internal/scheduler"
    }
  }
}

resource "google_logging_metric" "outbox_dispatch_failure" {
  project = var.project_id
  name    = "${local.prefix}-outbox-dispatch-failure"
  filter  = <<-EOT
    resource.type="cloud_run_revision"
    resource.labels.service_name="${google_cloud_run_v2_service.api.name}"
    jsonPayload.message:"Outbox dispatch failed"
  EOT
  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
  }
}

resource "google_monitoring_alert_policy" "cloud_run_5xx" {
  project               = var.project_id
  display_name          = "${local.prefix}: Cloud Run 5xx"
  combiner              = "OR"
  notification_channels = var.alert_notification_channel_names

  conditions {
    display_name = "5xx response count exceeds approved threshold"
    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"cloud_run_revision\"",
        "resource.label.\"service_name\"=\"${google_cloud_run_v2_service.api.name}\"",
        "metric.type=\"run.googleapis.com/request_count\"",
        "metric.label.\"response_code_class\"=\"5xx\""
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.cloud_run_5xx_count_threshold
      duration        = "0s"
      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_DELTA"
        cross_series_reducer = "REDUCE_SUM"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "outbox_dispatch_failure" {
  project               = var.project_id
  display_name          = "${local.prefix}: Outbox dispatch failures"
  combiner              = "OR"
  notification_channels = var.alert_notification_channel_names

  conditions {
    display_name = "Dispatch failures exceed approved threshold"
    condition_threshold {
      filter          = "resource.type=\"cloud_run_revision\" AND metric.type=\"logging.googleapis.com/user/${google_logging_metric.outbox_dispatch_failure.name}\""
      comparison      = "COMPARISON_GT"
      threshold_value = var.outbox_failure_count_threshold
      duration        = "0s"
      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_DELTA"
        cross_series_reducer = "REDUCE_SUM"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "cloud_sql_cpu" {
  project               = var.project_id
  display_name          = "${local.prefix}: Cloud SQL CPU"
  combiner              = "OR"
  notification_channels = var.alert_notification_channel_names

  conditions {
    display_name = "CPU utilization exceeds approved threshold"
    condition_threshold {
      filter          = "resource.type=\"cloudsql_database\" AND resource.label.\"database_id\"=\"${var.project_id}:${google_sql_database_instance.postgres.name}\" AND metric.type=\"cloudsql.googleapis.com/database/cpu/utilization\""
      comparison      = "COMPARISON_GT"
      threshold_value = var.cloud_sql_cpu_threshold
      duration        = "300s"
      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_MEAN"
      }
    }
  }
}
