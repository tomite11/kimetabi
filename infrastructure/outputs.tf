output "cloud_run_service_uri" {
  value = google_cloud_run_v2_service.api.uri
}

output "firebase_hosting_site" {
  value = google_firebase_hosting_site.application.site_id
}

output "cloud_sql_connection_name" {
  value = google_sql_database_instance.postgres.connection_name
}

output "receipt_bucket" {
  value = google_storage_bucket.receipts.name
}

output "database_secret_names" {
  value = { for key, secret in google_secret_manager_secret.database : key => secret.secret_id }
}
