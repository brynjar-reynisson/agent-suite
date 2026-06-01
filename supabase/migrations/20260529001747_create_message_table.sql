CREATE SEQUENCE message_id_seq START 1;

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

ALTER SEQUENCE message_id_seq OWNED BY message.message_id;
