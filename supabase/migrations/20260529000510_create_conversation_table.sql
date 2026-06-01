CREATE SEQUENCE conversation_id_seq START 1;

CREATE TABLE conversation (
    conversation_id   BIGINT                   NOT NULL DEFAULT nextval('conversation_id_seq'),
    user_id           BIGINT                   NOT NULL,
    conversation_name TEXT                     NOT NULL,
    start             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_conversation PRIMARY KEY (conversation_id),
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES suite_user (user_id)
);

ALTER SEQUENCE conversation_id_seq OWNED BY conversation.conversation_id;
