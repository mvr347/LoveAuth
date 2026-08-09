package me.lovelace.loveAuth.auth;

/**
 * Предпочитаемый способ входа игрока, если доступны оба (пароль и Discord).
 * Настраивается в /loveauth account — определяет, какой способ запускается
 * автоматически при заходе на сервер, без промежуточного экрана выбора.
 */
public enum AuthMethod {
    PASSWORD,
    DISCORD
}
