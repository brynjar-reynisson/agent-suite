CREATE INDEX idx_conversation_user_id    ON conversation(user_id);
CREATE INDEX idx_message_conversation_id ON message(conversation_id);
CREATE INDEX idx_message_user_id         ON message(user_id);
