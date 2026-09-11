variable "project_id" {
  description = "Google Cloud project ID for this environment."
  type        = string
}

variable "environment" {
  description = "Short environment name used in resource names and labels."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,12}$", var.environment))
    error_message = "environment must be 2-13 lowercase letters, digits, or hyphens."
  }
}

variable "hosting_site_id" {
  description = "Firebase Hosting site ID for this environment."
  type        = string
}

variable "backend_image" {
  description = "Immutable Artifact Registry image URI including a digest."
  type        = string

  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.backend_image))
    error_message = "backend_image must use an immutable sha256 digest."
  }
}

variable "backend_public_base_url" {
  description = "Approved HTTPS origin used by REST/WebSocket clients and as the OIDC audience base."
  type        = string

  validation {
    condition     = can(regex("^https://[A-Za-z0-9.-]+(?::[0-9]+)?$", var.backend_public_base_url))
    error_message = "backend_public_base_url must be an HTTPS origin without a trailing slash."
  }
}

variable "cors_allowed_origins" {
  description = "Exact Firebase Hosting production and approved preview origins. Wildcards are forbidden."
  type        = list(string)

  validation {
    condition = length(var.cors_allowed_origins) > 0 && alltrue([
      for origin in var.cors_allowed_origins :
      can(regex("^https://[A-Za-z0-9.-]+(?::[0-9]+)?$", origin)) && !strcontains(origin, "*")
    ])
    error_message = "Provide one or more exact HTTPS origins without wildcards or trailing slashes."
  }
}

variable "alert_notification_channel_names" {
  description = "Existing notification-channel names for two independent routes such as email and Slack."
  type        = list(string)

  validation {
    condition     = length(distinct(var.alert_notification_channel_names)) >= 2
    error_message = "At least two distinct approved notification channels are required."
  }
}

variable "cloud_run_5xx_count_threshold" {
  description = "Cloud Run 5xx threshold used with COMPARISON_GT; 4 means alert at five events in five minutes."
  type        = number
  default     = 4

  validation {
    condition     = var.cloud_run_5xx_count_threshold >= 0
    error_message = "cloud_run_5xx_count_threshold must not be negative."
  }
}

variable "outbox_failure_count_threshold" {
  description = "Outbox failure threshold used with COMPARISON_GT; zero means alert at the first event."
  type        = number
  default     = 0

  validation {
    condition     = var.outbox_failure_count_threshold >= 0
    error_message = "outbox_failure_count_threshold must not be negative."
  }
}

variable "cloud_sql_cpu_threshold" {
  description = "Cloud SQL CPU utilization threshold sustained for five minutes."
  type        = number
  default     = 0.8

  validation {
    condition     = var.cloud_sql_cpu_threshold > 0 && var.cloud_sql_cpu_threshold < 1
    error_message = "cloud_sql_cpu_threshold must be between 0 and 1."
  }
}

variable "region" {
  description = "Tokyo region required by SPEC."
  type        = string
  default     = "asia-northeast1"

  validation {
    condition     = var.region == "asia-northeast1"
    error_message = "MVP resources must remain in asia-northeast1."
  }
}

variable "cloud_sql_tier" {
  description = "Cloud SQL tier for the closed beta; reassess before public release."
  type        = string
  default     = "db-g1-small"

  validation {
    condition     = length(trimspace(var.cloud_sql_tier)) > 0
    error_message = "cloud_sql_tier must be approved for the target environment."
  }
}

variable "database_name" {
  type    = string
  default = "kimetabi"
}

variable "backup_start_time" {
  description = "UTC start time for the automated backup window (03:00 JST)."
  type        = string
  default     = "18:00"
}

variable "retained_backups" {
  description = "Number of daily automated backups retained for the closed beta."
  type        = number
  default     = 14

  validation {
    condition     = var.retained_backups >= 1
    error_message = "retained_backups must be at least one."
  }
}

variable "transaction_log_retention_days" {
  description = "PITR transaction-log retention for Cloud SQL Enterprise."
  type        = number
  default     = 7

  validation {
    condition     = var.transaction_log_retention_days >= 1 && var.transaction_log_retention_days <= 7
    error_message = "transaction_log_retention_days must be between one and seven."
  }
}

variable "outbox_recovery_schedule" {
  description = "UTC cron schedule for five-minute Outbox recovery."
  type        = string
  default     = "*/5 * * * *"
}
