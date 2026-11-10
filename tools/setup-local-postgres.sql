-- Run once as the local cluster administrator on a fresh PostgreSQL 16 server.
\set ON_ERROR_STOP on
\getenv app_password TIDYMAIL_DB_PASSWORD
\getenv test_password TIDYMAIL_TEST_DB_PASSWORD

CREATE ROLE tidymail LOGIN PASSWORD :'app_password';
CREATE ROLE tidymail_test LOGIN PASSWORD :'test_password';
CREATE DATABASE tidymail OWNER tidymail;
CREATE DATABASE tidymail_test OWNER tidymail_test;
CREATE DATABASE tidymail_test_migrations OWNER tidymail_test;

REVOKE CONNECT ON DATABASE tidymail FROM PUBLIC;
REVOKE CONNECT ON DATABASE tidymail_test FROM PUBLIC;
REVOKE CONNECT ON DATABASE tidymail_test_migrations FROM PUBLIC;
