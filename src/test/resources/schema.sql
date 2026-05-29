CREATE SEQUENCE suite_user_id_seq START WITH 1;

CREATE TABLE suite_user (
    user_id BIGINT NOT NULL DEFAULT nextval('suite_user_id_seq'),
    uuid    TEXT   NOT NULL,
    CONSTRAINT pk_suite_user PRIMARY KEY (user_id)
);

INSERT INTO suite_user (uuid) VALUES ('Guest');

CREATE SEQUENCE conversation_id_seq START WITH 1;

CREATE TABLE conversation (
    conversation_id   BIGINT                   NOT NULL DEFAULT nextval('conversation_id_seq'),
    user_id           BIGINT                   NOT NULL,
    conversation_name TEXT                     NOT NULL,
    create_time       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    root_directory    TEXT,
    CONSTRAINT pk_conversation PRIMARY KEY (conversation_id),
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES suite_user (user_id)
);

CREATE SEQUENCE message_id_seq START WITH 1;

CREATE TABLE message (
    message_id      BIGINT                   NOT NULL DEFAULT nextval('message_id_seq'),
    user_id         BIGINT                   NOT NULL,
    conversation_id BIGINT                   NOT NULL,
    type            TEXT                     NOT NULL,
    message         TEXT                     NOT NULL,
    message_time    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_message PRIMARY KEY (message_id),
    CONSTRAINT fk_message_user FOREIGN KEY (user_id) REFERENCES suite_user (user_id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (conversation_id)
);
