package me.lovelace.loveAuth.discord;

import com.google.gson.Gson;
import dev.lovelace.lovecore.api.discord.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import me.lovelace.loveAuth.LoveAuth;
import me.lovelace.loveAuth.auth.AuthManager;
import me.lovelace.loveAuth.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Shared gateway transport. Requests are bound to owner, nonce, message, IP and deadline. */
final class CoreAuthMenu implements DiscordInteractionListener, AutoCloseable {
  record Request(
      UUID uuid,
      String owner,
      String action,
      String ip,
      String message,
      long deadline,
      String passwordHash) {
    Request(UUID uuid, String owner, String action, String ip, String message, long deadline) {
      this(uuid, owner, action, ip, message, deadline, null);
    }

    boolean matches(String user, String messageId, long now) {
      return owner.equals(user) && message.equals(messageId) && now < deadline;
    }
  }

  private record Login(UUID uuid, String name, String ip, String nonce) {}

  private final LoveAuth plugin;
  private final DiscordAuthManager manager;
  private final DatabaseManager database;
  private final AuthManager auth;
  private final DiscordService service;
  private final java.util.concurrent.Semaphore sends = new java.util.concurrent.Semaphore(64);
  private final Map<String, String> latestAction = new ConcurrentHashMap<>();
  private volatile boolean closed;
  private final TemporaryDiscordMessages temporary;
  private final LoginProofs latestLogin = new LoginProofs();
  private final Map<String, Request> requests = new ConcurrentHashMap<>();
  private final Gson json = new Gson();

  CoreAuthMenu(
      LoveAuth p,
      DiscordAuthManager m,
      DatabaseManager db,
      AuthManager auth,
      DiscordService service) {
    this.plugin = p;
    manager = m;
    database = db;
    this.auth = auth;
    this.service = service;
    temporary =
        new TemporaryDiscordMessages(
            this::delete,
            (action, seconds) -> {
              if (closed) action.run();
              else
                plugin
                    .getServer()
                    .getAsyncScheduler()
                    .runDelayed(plugin, task -> action.run(), seconds, TimeUnit.SECONDS);
            });
  }

  void start() {
    try {
      service
          .getClass()
          .getMethod("claimInteractionPrefix", String.class)
          .invoke(service, "la:core:");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Обновите LoveCore", e);
    }
    service.registerInteractionListener(this);
    database
        .discordPlayers()
        .thenAccept(
            players ->
                players.forEach(
                    rec -> {
                      if (!closed) {
                        manager.cacheLink(rec.uuid(), rec.discordId());
                        service.setLink(rec.uuid(), rec.discordId());
                      }
                    }));
    try {
      var method =
          service
              .getClass()
              .getMethod("registerPrivateMenu", String.class, String.class, Function.class);
      method.invoke(
          service, "account", "Мой аккаунт", (Function<String, Map<String, Object>>) this::account);
      method.invoke(
          service,
          "security",
          "Безопасность",
          (Function<String, Map<String, Object>>) this::account);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Обновите LoveCore для общего меню", e);
    }
  }

  private Map<String, Object> account(String owner) {
    var r = database.findPlayerByDiscordId(owner).join();
    if (r.isEmpty())
      return body(
          "**Мой аккаунт**\nАккаунт не привязан. Получите код командой привязки в игре.",
          List.of(button("la:core:link", "Ввести код")));
    var rec = r.get();
    var controls = new ArrayList<Map<String, Object>>();
    controls.add(button("la:core:sessions", "Сбросить сессии"));
    controls.add(button("la:core:password", "Сменить пароль"));
    controls.add(
        button(
            rec.locked() ? "la:core:unlock" : "la:core:lock",
            rec.locked() ? "Разблокировать" : "Заблокировать аккаунт"));
    if (rec.hasPassword() && rec.passwordEnabled())
      controls.add(button("la:core:unlink", "Отвязать Discord"));
    return body(
        "**Аккаунт "
            + rec.username()
            + "**\nСостояние: "
            + (rec.locked() ? "заблокирован" : "активен")
            + "\nПароль: "
            + (rec.hasPassword() && rec.passwordEnabled() ? "установлен" : "не установлен"),
        controls);
  }

  boolean pendingLogin(UUID uuid,String owner) {
    long now=System.currentTimeMillis();
    return !closed && requests.values().stream().anyMatch(r->r.uuid.equals(uuid)&&r.owner.equals(owner)&&r.action.equals("LOGIN")&&r.deadline>now);
  }

  CompletableFuture<Boolean> login(Player player) {
    return manager
        .onMain(
            () ->
                player.isOnline()
                    ? new Login(
                        player.getUniqueId(),
                        player.getName(),
                        auth.getIp(player),
                        latestLogin.begin(player.getUniqueId()))
                    : null)
        .thenCompose(
            snapshot -> {
              if (snapshot == null) return CompletableFuture.completedFuture(false);
              return database
                  .findPlayer(snapshot.uuid)
                  .thenCompose(
                      record -> {
                        if (record.isEmpty()
                            || !record.get().hasDiscord()
                            || !latestLogin.current(snapshot.uuid, snapshot.nonce))
                          return CompletableFuture.completedFuture(false);
                        return send(
                            snapshot.uuid,
                            record.get().discordId(),
                            "LOGIN",
                            snapshot.ip,
                            "**Это вы входите?**\nИгрок: "
                                + snapshot.name
                                + "\nАдрес: "
                                + snapshot.ip,
                            snapshot.nonce,
                            snapshot.name);
                      });
            });
  }

  CompletableFuture<Boolean> confirmation(UUID uuid, String action) {
    return database
        .findPlayer(uuid)
        .thenCompose(
            record -> {
              if (record.isEmpty() || !record.get().hasDiscord())
                return CompletableFuture.completedFuture(false);
              return send(
                  uuid,
                  record.get().discordId(),
                  action,
                  "",
                  "**Подтверждение безопасности**\nДействие: " + actionName(action));
            });
  }

  private CompletableFuture<Boolean> send(
      UUID uuid, String owner, String action, String ip, String text) {
    if (action.equals("LOGIN")) return CompletableFuture.completedFuture(false);
    return send(uuid, owner, action, ip, text, UUID.randomUUID().toString().replace("-", ""), null);
  }

  private CompletableFuture<Boolean> send(
      UUID uuid,
      String owner,
      String action,
      String ip,
      String text,
      String nonce,
      String playerName) {
    if (action.equals("LOGIN") && !latestLogin.current(uuid, nonce))
      return CompletableFuture.completedFuture(false);
    if (closed || requests.size() >= 1024 || !sends.tryAcquire())
      return CompletableFuture.completedFuture(false);
    String actionKey = uuid + ":" + action;
    latestAction.put(actionKey, nonce);
    discard(uuid, action);
    // A newer login invalidates the older one before any network response can approve it.
    String passwordHash =
        action.equals("PASSWORD_CHANGE") ? manager.corePasswordSnapshot(uuid) : null;
    Map<String, Object> card =
        body(
            text,
            List.of(
                button("la:core:yes:" + nonce, "Подтвердить"),
                button("la:core:no:" + nonce, "Отклонить"),
                button("lc:menu:home", "Главное меню")));
    if (action.equals("LOGIN")) {
      var lang = plugin.getLangManager();
      card =
          SecurityCards.login(
              lang.plain("discord.login-embed-title"),
              lang.plain("discord.login-embed-desc", Map.of("player", playerName)),
              nonce,
              lang.plain("discord.login-btn-confirm"),
              lang.plain("discord.login-btn-deny"));
    }
    return service
        .directMessage(owner, json.toJson(card))
        .thenApply(
            message -> {
              if (message == null) return false;
              if (closed || !nonce.equals(latestAction.get(actionKey))) {
                delete(owner, message);
                return false;
              }
              if (action.equals("LOGIN") && !latestLogin.current(uuid, nonce)) {
                delete(owner, message);
                return false;
              }
              var request =
                  new Request(
                      uuid,
                      owner,
                      action,
                      ip,
                      message,
                      System.currentTimeMillis() + 120000,
                      passwordHash);
              requests.put(nonce, request);
              if (action.equals("LOGIN"))
                manager.onMain(
                    () -> {
                      var player = Bukkit.getPlayer(uuid);
                      if (player != null && latestLogin.current(uuid, nonce))
                        plugin.getLangManager().send(player, "discord.login-dm-sent");
                      return true;
                    });
              plugin
                  .getServer()
                  .getAsyncScheduler()
                  .runDelayed(
                      plugin,
                      t -> {
                        if (requests.remove(nonce, request)) {
                          latestAction.remove(actionKey, nonce);
                          latestLogin.cancel(uuid, nonce);
                          edit(request, "Запрос истёк.");
                        }
                      },
                      120,
                      TimeUnit.SECONDS);
              return true;
            })
        .whenComplete((result, error) -> sends.release());
  }

  private void delete(String owner, String message) {
    try {
      service
          .getClass()
          .getMethod("deleteDirectMessage", String.class, String.class)
          .invoke(service, owner, message);
    } catch (ReflectiveOperationException error) {
      plugin.getLogger().fine("Не удалена временная карточка Discord");
    }
  }

  private void discard(UUID uuid, String action) {
    for (var entry : requests.entrySet()) {
      var request = entry.getValue();
      if (request.uuid.equals(uuid)
          && (action == null || request.action.equals(action))
          && requests.remove(entry.getKey(), request)) delete(request.owner, request.message);
    }
  }

  private void edit(Request r, String text) {
    var resolved = new LinkedHashMap<String, Object>(body(text, List.of()));
    resolved.put("embeds", List.of());
    try {
      var update =
          (CompletionStage<?>)
              service
                  .getClass()
                  .getMethod("editDirectMessage", String.class, String.class, String.class)
                  .invoke(service, r.owner, r.message, json.toJson(resolved));
      temporary.resolved(r.owner, r.message, update);
    } catch (ReflectiveOperationException error) {
      delete(r.owner, r.message);
    }
  }

  @Override
  public boolean onInteraction(DiscordInteraction i) {
    if (Set.of(
            "привязать",
            "отвязать",
            "заблокировать",
            "разблокировать",
            "пароль",
            "сессии",
            "инфо",
            "онлайн",
            "помощь")
        .contains(i.name())) {
      service.respond(
          i,
          json.toJson(
              Map.of(
                  "type",
                  4,
                  "data",
                  Map.of(
                      "content",
                      "Аккаунт и безопасность доступны в общем личном кабинете.",
                      "flags",
                      64,
                      "components",
                      List.of(
                          Map.of(
                              "type",
                              1,
                              "components",
                              List.of(button("lc:menu:home", "Личный кабинет"))))))));
      return true;
    }
    if (!i.name().startsWith("la:core:")) return false;
    String action = i.name().substring("la:core:".length());
    if (action.equals("link") || action.equals("password")) {
      String field = action.equals("link") ? "code" : "password";
      var fields =
          new ArrayList<Map<String, Object>>(
              List.of(
                  Map.of(
                      "type",
                      1,
                      "components",
                      List.of(
                          Map.of(
                              "type",
                              4,
                              "custom_id",
                              field,
                              "label",
                              action.equals("link") ? "Код из игры" : "Новый пароль",
                              "style",
                              1,
                              "required",
                              true,
                              "min_length",
                              action.equals("link")
                                  ? 1
                                  : Math.max(3, plugin.getConfigManager().getMinPasswordLength()),
                              "max_length",
                              action.equals("link") ? 32 : PasswordInput.MAX_LENGTH)))));
      if (action.equals("password"))
        fields.add(
            Map.of(
                "type",
                1,
                "components",
                List.of(
                    Map.of(
                        "type",
                        4,
                        "custom_id",
                        "repeat",
                        "label",
                        "Повторите пароль",
                        "style",
                        1,
                        "required",
                        true,
                        "max_length",
                        PasswordInput.MAX_LENGTH))));
      service.respond(
          i,
          json.toJson(
              Map.of(
                  "type",
                  9,
                  "data",
                  Map.of(
                      "custom_id",
                      "la:core:submit:" + action,
                      "title",
                      action.equals("link") ? "Привязка аккаунта" : "Смена пароля",
                      "components",
                      fields))));
      return true;
    }
    service
        .respond(i, i.type() == 5 ? "{\"type\":5,\"data\":{\"flags\":64}}" : "{\"type\":6}")
        .thenAccept(
            acknowledged -> {
              if (!acknowledged || closed) return;
              if (action.startsWith("yes:") || action.startsWith("no:")) {
                answer(i, action);
                return;
              }
              if (action.equals("submit:link")) {
                manager
                    .completeCoreLink(i.userId(), i.values().getOrDefault("code", ""))
                    .thenAccept(
                        ok ->
                            service.editResponse(
                                i, ok ? "Аккаунт привязан." : "Код не найден или истёк."));
                return;
              }
              database
                  .findPlayerByDiscordId(i.userId())
                  .thenAccept(
                      record -> {
                        if (record.isEmpty()) {
                          service.editResponse(i, "Аккаунт не привязан.");
                          return;
                        }
                        var rec = record.get();
                        if (action.equals("submit:password")) {
                          String pass = i.values().getOrDefault("password", "");
                          if (PasswordInput.validate(
                                  pass,
                                  i.values().getOrDefault("repeat", ""),
                                  Math.max(3, plugin.getConfigManager().getMinPasswordLength()),
                                  PasswordInput.MAX_LENGTH)
                              != PasswordInput.Result.OK) {
                            service.editResponse(i, "Пароль не соответствует требованиям сервера.");
                            return;
                          }
                          auth.hashPasswordAsync(pass)
                              .thenAccept(
                                  hash -> {
                                    manager.prepareCorePassword(rec.uuid(), hash);
                                    confirmation(rec.uuid(), "PASSWORD_CHANGE");
                                  });
                        } else {
                          String domain =
                              switch (action) {
                                case "lock" -> "LOCK_ACCOUNT";
                                case "unlock" -> "UNLOCK_ACCOUNT";
                                case "unlink" -> "UNLINK";
                                case "sessions" -> "SESSIONS";
                                default -> "";
                              };
                          if (!domain.isEmpty()) confirmation(rec.uuid(), domain);
                        }
                      });
            });
    return true;
  }

  private void answer(DiscordInteraction i, String action) {
    String nonce = action.substring(action.indexOf(':') + 1);
    Request r = requests.get(nonce);
    if (r == null
        || (r.action.equals("LOGIN") && !latestLogin.current(r.uuid, nonce))
        || !r.matches(i.userId(), i.values().get("_messageId"), System.currentTimeMillis())
        || !nonce.equals(latestAction.get(r.uuid + ":" + r.action))) {
      service.editResponse(i, "Запрос недоступен или истёк.");
      return;
    }
    database
        .findPlayerByDiscordId(i.userId())
        .thenAccept(
            record -> {
              if (record.isEmpty()
                  || !record.get().uuid().equals(r.uuid)
                  || !requests.remove(nonce, r)) return;
              var rec = record.get();
              if (action.startsWith("no:")) {
                if (r.action.equals("LOGIN"))
                  manager.onMain(
                      () -> {
                        var p = Bukkit.getPlayer(r.uuid);
                        if (p != null
                            && latestLogin.current(r.uuid, nonce)
                            && auth.getIp(p).equals(r.ip))
                          p.kick(
                              net.kyori.adventure.text.Component.text(
                                  "Вход отклонён владельцем Discord."));
                        return true;
                      });
                edit(r, "Действие отклонено.");
                return;
              }
              if (r.action.equals("LOGIN")) {
                if (rec.locked()) {
                  edit(r, "Аккаунт заблокирован. Вход не подтверждён.");
                  return;
                }
                manager
                    .onMain(
                        () -> {
                          var p = Bukkit.getPlayer(r.uuid);
                          if (p == null
                              || !latestLogin.current(r.uuid, nonce)
                              || !auth.getIp(p).equals(r.ip)) return false;
                          auth.markAuthenticated(p, true);
                          return true;
                        })
                    .thenAccept(
                        ok ->
                            edit(
                                r,
                                ok
                                    ? "Вход подтверждён."
                                    : "Подключение изменилось. Повторите вход."));
              } else if (r.action.equals("UNLINK")
                  && (!rec.hasPassword() || !rec.passwordEnabled()))
                edit(r, "Сначала установите пароль.");
              else if (r.action.equals("SESSIONS"))
                auth.getSessionManager()
                    .invalidate(r.uuid)
                    .thenRun(() -> edit(r, "Сессии сброшены."));
              else if (r.action.equals("UNLOCK_ACCOUNT"))
                database.setLocked(r.uuid, false).thenRun(() -> edit(r, "Аккаунт разблокирован."));
              else if (r.action.equals("PASSWORD_CHANGE"))
                manager
                    .applyCorePassword(r.uuid, r.passwordHash)
                    .thenAccept(
                        ok -> edit(r, ok ? "Пароль изменён." : "Запрос смены пароля устарел."));
              else {
                if (Set.of("LOCK_ACCOUNT", "UNLINK").contains(r.action)) clear(r.uuid);
                manager.runConfirmed(rec, r.action).thenAccept(text -> edit(r, text));
              }
            });
  }

  private static String actionName(String action) {
    return switch (action) {
      case "PASSWORD_CHANGE" -> "смена пароля";
      case "LOCK_ACCOUNT" -> "блокировка аккаунта";
      case "UNLOCK_ACCOUNT" -> "разблокировка аккаунта";
      case "UNLINK" -> "отвязка Discord";
      case "SESSIONS" -> "сброс сессий";
      default -> action;
    };
  }

  private static Map<String, Object> button(String id, String label) {
    return SecurityCards.button(id, label);
  }

  private static Map<String, Object> body(String text, List<Map<String, Object>> buttons) {
    return Map.of(
        "content",
        text,
        "allowed_mentions",
        Map.of("parse", List.of()),
        "components",
        buttons.isEmpty() ? List.of() : List.of(Map.of("type", 1, "components", buttons)));
  }

  void clear(UUID uuid) {
    latestAction.keySet().removeIf(key -> key.startsWith(uuid + ":"));
    latestLogin.cancel(uuid);
    discard(uuid, null);
  }

  @Override
  public void close() {
    closed = true;
    latestAction.clear();
    latestLogin.clear();
    service.unregisterInteractionListener(this);
    for (var request : requests.values()) delete(request.owner, request.message);
    requests.clear();
    for (String id : List.of("account", "security"))
      try {
        service.getClass().getMethod("unregisterPrivateMenu", String.class).invoke(service, id);
      } catch (ReflectiveOperationException ignored) {
      }
  }
}
