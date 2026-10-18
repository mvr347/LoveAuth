package me.lovelace.loveAuth.discord;

import org.junit.jupiter.api.Test;

import static me.lovelace.loveAuth.discord.PasswordInput.Result.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PasswordInputTest {

    @Test
    void acceptsMatchingValidPassword() {
        assertEquals(OK, PasswordInput.validate("Secret_123", "Secret_123", 6, 25));
    }

    @Test
    void rejectsLengthOutOfRange() {
        assertEquals(LENGTH, PasswordInput.validate("abc", "abc", 6, 25));
        assertEquals(LENGTH, PasswordInput.validate("a".repeat(26), "a".repeat(26), 6, 25));
        assertEquals(LENGTH, PasswordInput.validate(null, null, 6, 25));
        assertEquals(OK, PasswordInput.validate("a".repeat(25), "a".repeat(25), 6, 25));
    }

    @Test
    void rejectsForbiddenCharacters() {
        assertEquals(CHARSET_INVALID, PasswordInput.validate("пароль123", "пароль123", 6, 25));
        assertEquals(CHARSET_INVALID, PasswordInput.validate("pass word1", "pass word1", 6, 25));
    }

    @Test
    void rejectsMismatch() {
        assertEquals(MISMATCH, PasswordInput.validate("Secret_123", "Secret_124", 6, 25));
        assertEquals(MISMATCH, PasswordInput.validate("Secret_123", null, 6, 25));
    }
}
