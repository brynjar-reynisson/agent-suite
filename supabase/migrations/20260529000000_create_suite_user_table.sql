CREATE SEQUENCE suite_user_id_seq START 1;

CREATE TABLE suite_user (
    user_id BIGINT NOT NULL DEFAULT nextval('suite_user_id_seq'),
    uuid    TEXT   NOT NULL,
    CONSTRAINT pk_suite_user PRIMARY KEY (user_id)
);

ALTER SEQUENCE suite_user_id_seq OWNED BY suite_user.user_id;

INSERT INTO suite_user (uuid) VALUES ('Guest');
