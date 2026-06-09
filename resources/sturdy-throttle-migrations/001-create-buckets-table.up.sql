CREATE TABLE api_minute_buckets (
  organization_id BLOB    NOT NULL,
  rate_key        TEXT    NOT NULL,
  bucket_start_ms INTEGER NOT NULL,
  request_count   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (organization_id, rate_key, bucket_start_ms)
);
