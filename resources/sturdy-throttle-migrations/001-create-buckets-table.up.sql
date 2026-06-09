CREATE TABLE api_minute_buckets (
  organization_id BLOB    NOT NULL,
  bucket_start_ms INTEGER NOT NULL,
  request_count   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (organization_id, bucket_start_ms)
);
