CREATE TABLE user_role (
    user_id    BIGINT      NOT NULL REFERENCES suite_user(user_id) ON DELETE CASCADE,
    role       TEXT        NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role)
);

INSERT INTO user_role (user_id, role)
SELECT user_id, 'admin' FROM suite_user WHERE email = 'breynisson@gmail.com';
