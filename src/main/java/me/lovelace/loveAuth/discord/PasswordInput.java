package me.lovelace.loveAuth.discord;

import java.util.regex.Pattern;

/** Validation of the two fields of the Discord password-change modal. */
public final class PasswordInput {
    private PasswordInput() {}

    public static final int MAX_LENGTH = 25;
    public static final Pattern CHARSET = Pattern.compile("^[a-zA-Z0-9._\\-!@#$%^&*()]+$");

    public enum Result { OK, LENGTH, CHARSET_INVALID, MISMATCH }

    /**
     * @param minLength effective minimum length (already clamped by the caller)
     */
    public static Result validate(String password, String repeat, int minLength, int maxLength) {
        if (password == null || password.length() < minLength || password.length() > maxLength) return Result.LENGTH;
        if (!CHARSET.matcher(password).matches()) return Result.CHARSET_INVALID;
        if (!password.equals(repeat)) return Result.MISMATCH;
        return Result.OK;
    }
}
