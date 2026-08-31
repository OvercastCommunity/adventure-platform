/*
 * This file is part of adventure-platform, licensed under the MIT License.
 *
 * Copyright (c) 2018-2020 KyoriPowered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.kyori.adventure.platform.bungeecord;

import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.permission.PermissionChecker;
import net.kyori.adventure.platform.facet.Facet;
import net.kyori.adventure.platform.facet.FacetBase;
import net.kyori.adventure.platform.facet.FacetComponentFlattener;
import net.kyori.adventure.platform.facet.FacetPointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import net.kyori.adventure.text.serializer.json.legacyimpl.NBTLegacyHoverEventSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.util.TriState;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.chat.TranslationRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static net.kyori.adventure.platform.bungeecord.BungeeReflection.findMethod;
import static net.kyori.adventure.platform.bungeecord.BungeeReflection.hasMethod;
import static net.kyori.adventure.platform.facet.Knob.logError;

class BungeeFacet<V extends CommandSender> extends FacetBase<V> {
  static final BaseComponent[] EMPTY_COMPONENT_ARRAY = new BaseComponent[0];

  private static final Collection<? extends FacetComponentFlattener.Translator<ProxyServer>> TRANSLATORS = Facet.of(
    BungeeFacet.Translator::new
  );
  static final ComponentFlattener FLATTENER = FacetComponentFlattener.get(ProxyServer.getInstance(), TRANSLATORS);
  static final BungeeComponentSerializer MODERN = BungeeComponentSerializer.of(
    GsonComponentSerializer.gson(),
    LegacyComponentSerializer.builder()
      .hexColors()
      .useUnusualXRepeatedCharacterHexFormat()
      .flattener(FLATTENER)
      .build()
  );
  static final BungeeComponentSerializer LEGACY = BungeeComponentSerializer.of(
    GsonComponentSerializer.colorDownsamplingGson()
      .toBuilder()
      .editOptions(options -> options.value(JSONOptions.EMIT_HOVER_EVENT_TYPE, JSONOptions.HoverEventValueMode.ALL))
      .legacyHoverEventSerializer(NBTLegacyHoverEventSerializer.get())
      .build(),
    LegacyComponentSerializer.builder().flattener(FLATTENER).build()
  );

  protected BungeeFacet(final @Nullable Class<? extends V> viewerClass) {
    super(viewerClass);
  }

  static class ChatConsole extends BungeeFacet<CommandSender> implements Facet.Chat<CommandSender, BaseComponent[]> {
    protected ChatConsole() {
      super(CommandSender.class);
    }

    @Override
    public boolean isApplicable(final @NonNull CommandSender viewer) {
      return super.isApplicable(viewer) && !(viewer instanceof Connection); // Console only accepts legacy formatting
    }

    @Override
    public BaseComponent @NonNull[] createMessage(final @NonNull CommandSender viewer, final @NonNull Component message) {
      return LEGACY.serialize(message);
    }

    @Override
    public void sendMessage(final @NonNull CommandSender viewer, final BaseComponent @NonNull [] message) {
      viewer.sendMessage(message);
    }

    @Override
    public void sendMessage(final @NonNull CommandSender viewer, final BaseComponent @NonNull [] message, final ChatType.@NonNull Bound boundChatType) {
      viewer.sendMessage(message);
    }
  }

  static class Message extends BungeeFacet<ProxiedPlayer> implements Facet.Message<ProxiedPlayer, BaseComponent[]> {
    protected Message() {
      super(ProxiedPlayer.class);
    }

    @Override
    public BaseComponent @NonNull[] createMessage(final @NonNull ProxiedPlayer viewer, final @NonNull Component message) {
      if (viewer.getPendingConnection().getVersion() >= PROTOCOL_HEX_COLOR) {
        return MODERN.serialize(message);
      } else {
        return LEGACY.serialize(message);
      }
    }
  }

  static class ChatPlayerSenderId extends ChatPlayer implements Facet.Chat<ProxiedPlayer, BaseComponent[]> {
    private static final boolean SUPPORTED;

    static {
      boolean supported;
      try {
        ProxiedPlayer.class.getMethod("sendMessage", UUID.class, BaseComponent.class);
        supported = true;
      } catch (final NoSuchMethodException ex) {
        supported = false;
      }

      SUPPORTED = supported;
    }

    @Override
    public boolean isSupported() {
      return super.isSupported() && SUPPORTED;
    }

    @Override
    public void sendMessage(final @NonNull ProxiedPlayer viewer, final BaseComponent @NonNull [] message, final ChatType.@NonNull Bound boundChatType) {
      viewer.sendMessage(Identity.nil().uuid(), message);
    }

    @Override
    public void sendMessage(final @NonNull ProxiedPlayer viewer, final BaseComponent @NonNull [] message, final @NonNull SignedMessage signedMessage, final ChatType.@NonNull Bound boundChatType) {
      viewer.sendMessage(signedMessage.identity().uuid(), message);
    }
  }

  static class ChatPlayer extends Message implements Facet.Chat<ProxiedPlayer, BaseComponent[]> {
    @Override
    public void sendMessage(final @NonNull ProxiedPlayer viewer, final BaseComponent @NonNull [] message) {
      viewer.sendMessage(ChatMessageType.SYSTEM, message);
    }

    @Override
    public void sendMessage(final @NonNull ProxiedPlayer viewer, final BaseComponent @NonNull [] message, final ChatType.@NonNull Bound boundChatType) {
      viewer.sendMessage(ChatMessageType.CHAT, message);
    }
  }

  static class ActionBar extends Message implements Facet.ActionBar<ProxiedPlayer, BaseComponent[]> {
    @Override
    public void sendMessage(final @NonNull ProxiedPlayer viewer, final BaseComponent @NonNull[] message) {
      viewer.sendMessage(ChatMessageType.ACTION_BAR, message);
    }
  }

  static class Title extends Message implements Facet.Title<ProxiedPlayer, BaseComponent[], net.md_5.bungee.api.Title, net.md_5.bungee.api.Title> {
    private static final net.md_5.bungee.api.Title CLEAR = ProxyServer.getInstance().createTitle().clear();
    private static final net.md_5.bungee.api.Title RESET = ProxyServer.getInstance().createTitle().reset();

    @Override
    public net.md_5.bungee.api.@NonNull Title createTitleCollection() {
      return ProxyServer.getInstance().createTitle();
    }

    @Override
    public void contributeTitle(final net.md_5.bungee.api.@NonNull Title coll, final BaseComponent@NonNull[] title) {
      coll.title(title);
    }

    @Override
    public void contributeSubtitle(final net.md_5.bungee.api.@NonNull Title coll, final BaseComponent@NonNull[] subtitle) {
      coll.subTitle(subtitle);
    }

    @Override
    public void contributeTimes(final net.md_5.bungee.api.@NonNull Title coll, final int inTicks, final int stayTicks, final int outTicks) {
      if (inTicks > -1) coll.fadeIn(inTicks);
      if (stayTicks > -1) coll.stay(stayTicks);
      if (outTicks > -1) coll.fadeOut(outTicks);
    }

    @Override
    public net.md_5.bungee.api.@Nullable Title completeTitle(final net.md_5.bungee.api.@NonNull Title coll) {
      return coll;
    }

    @Override
    public void showTitle(final @NonNull ProxiedPlayer viewer, final net.md_5.bungee.api.@NonNull Title title) {
      viewer.sendTitle(title);
    }

    @Override
    public void clearTitle(final @NonNull ProxiedPlayer viewer) {
      viewer.sendTitle(CLEAR);
    }

    @Override
    public void resetTitle(final @NonNull ProxiedPlayer viewer) {
      viewer.sendTitle(RESET);
    }
  }

  static class BossBar extends Message implements BossBarPacket<ProxiedPlayer> {
    private static MethodHandle SET_TITLE_STRING;
    private static MethodHandle SET_TITLE_COMPONENT;

    static {
      final Class<?> bossBarClass = net.md_5.bungee.protocol.packet.BossBar.class;
      if (hasMethod(bossBarClass, "setTitle", String.class)) {
        SET_TITLE_STRING = findMethod(bossBarClass, "setTitle", void.class, String.class);
      } else {
        SET_TITLE_COMPONENT = findMethod(bossBarClass, "setTitle", void.class, BaseComponent.class);
      }
    }

    private final Set<ProxiedPlayer> viewers;
    private final net.md_5.bungee.protocol.packet.BossBar bar;
    private volatile boolean initialized = false;

    protected BossBar(final @NonNull Collection<ProxiedPlayer> viewers) {
      super();
      this.viewers = new CopyOnWriteArraySet<>(viewers);
      this.bar = new net.md_5.bungee.protocol.packet.BossBar(UUID.randomUUID(), ACTION_ADD);
    }

    static class Builder extends BungeeFacet<ProxiedPlayer> implements Facet.BossBar.Builder<ProxiedPlayer, net.kyori.adventure.platform.bungeecord.BungeeFacet.BossBar> {
      protected Builder() {
        super(ProxiedPlayer.class);
      }

      @Override
      public boolean isApplicable(final @NonNull ProxiedPlayer viewer) {
        return super.isApplicable(viewer) && viewer.getPendingConnection().getVersion() >= PROTOCOL_BOSS_BAR;
      }

      @Override
      public net.kyori.adventure.platform.bungeecord.BungeeFacet.@NonNull BossBar createBossBar(final @NonNull Collection<ProxiedPlayer> viewers) {
        return new net.kyori.adventure.platform.bungeecord.BungeeFacet.BossBar(viewers);
      }
    }

    @Override
    public void bossBarInitialized(final net.kyori.adventure.bossbar.@NonNull BossBar bar) {
      BossBarPacket.super.bossBarInitialized(bar);
      this.initialized = true;
      this.broadcastPacket(ACTION_ADD);
    }

    @Override
    public void bossBarNameChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final @NonNull Component oldName, final @NonNull Component newName) {
      if (!this.viewers.isEmpty()) {
        final BaseComponent[] message = this.createMessage(this.viewers.iterator().next(), newName);
        this.updateBarTitle(message);
        this.broadcastPacket(ACTION_TITLE);
      }
    }

    @Override
    public void bossBarProgressChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final float oldPercent, final float newPercent) {
      this.bar.setHealth(newPercent);
      this.broadcastPacket(ACTION_HEALTH);
    }

    @Override
    public void bossBarColorChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final net.kyori.adventure.bossbar.BossBar.@NonNull Color oldColor, final net.kyori.adventure.bossbar.BossBar.@NonNull Color newColor) {
      this.bar.setColor(this.createColor(newColor));
      this.broadcastPacket(ACTION_STYLE);
    }

    @Override
    public void bossBarOverlayChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final net.kyori.adventure.bossbar.BossBar.@NonNull Overlay oldOverlay, final net.kyori.adventure.bossbar.BossBar.@NonNull Overlay newOverlay) {
      this.bar.setDivision(this.createOverlay(newOverlay));
      this.broadcastPacket(ACTION_STYLE);
    }

    @Override
    public void bossBarFlagsChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final @NonNull Set<net.kyori.adventure.bossbar.BossBar.Flag> flagsAdded, final @NonNull Set<net.kyori.adventure.bossbar.BossBar.Flag> flagsRemoved) {
      this.bar.setFlags(this.createFlag(this.bar.getFlags(), flagsAdded, flagsRemoved));
      this.broadcastPacket(ACTION_FLAG);
    }

    @Override
    public void addViewer(final @NonNull ProxiedPlayer viewer) {
      this.viewers.add(viewer);
      this.sendPacket(ACTION_ADD, viewer);
    }

    @Override
    public void removeViewer(final @NonNull ProxiedPlayer viewer) {
      this.viewers.remove(viewer);
      this.sendPacket(ACTION_REMOVE, viewer);
    }

    @Override
    public boolean isEmpty() {
      return !this.initialized || this.viewers.isEmpty();
    }

    @Override
    public void close() {
      this.broadcastPacket(ACTION_REMOVE);
      this.viewers.clear();
    }

    private void broadcastPacket(final int action) {
      if (this.isEmpty()) return;

      synchronized (this.bar) {
        this.bar.setAction(action);
        for (final ProxiedPlayer viewer : this.viewers) {
          viewer.unsafe().sendPacket(this.bar);
        }
      }
    }

    private void updateBarTitle(final BaseComponent[] message) {
      try {
        if (SET_TITLE_STRING != null) {
          SET_TITLE_STRING.invoke(this.bar, ComponentSerializer.toString(message));
        } else {
          SET_TITLE_COMPONENT.invoke(this.bar, TextComponent.fromArray(message));
        }
      } catch (final Throwable throwable) {
        logError(throwable, "Cannot update the BossBar title");
      }
    }

    private void sendPacket(final int action, final ProxiedPlayer... viewers) {
      synchronized (this.bar) {
        final int lastAction = this.bar.getAction();
        this.bar.setAction(action);
        for (final ProxiedPlayer viewer : viewers) {
          viewer.unsafe().sendPacket(this.bar);
        }
        this.bar.setAction(lastAction);
      }
    }
  }

  static final class TabList extends Message implements Facet.TabList<ProxiedPlayer, BaseComponent[]> {

    @Override
    public void send(final ProxiedPlayer viewer, final BaseComponent@Nullable[] header, final BaseComponent@Nullable[] footer) {
      viewer.setTabHeader(
        header == null ? EMPTY_COMPONENT_ARRAY : header,
        footer == null ? EMPTY_COMPONENT_ARRAY : footer);
    }
  }

  static final class CommandSenderPointers extends BungeeFacet<CommandSender> implements Facet.Pointers<CommandSender> {
    CommandSenderPointers() {
      super(CommandSender.class);
    }

    @Override
    public void contributePointers(final CommandSender viewer, final net.kyori.adventure.pointer.Pointers.Builder builder) {
      builder.withDynamic(Identity.NAME, viewer::getName);
      // todo: bungee doesn't expose any sort of TriState/isPermissionSet value :((((
      builder.withStatic(PermissionChecker.POINTER, perm -> viewer.hasPermission(perm) ? TriState.TRUE : TriState.FALSE);
      if (!(viewer instanceof ProxiedPlayer)) {
        builder.withStatic(FacetPointers.TYPE, viewer == ProxyServer.getInstance().getConsole() ? FacetPointers.Type.CONSOLE : FacetPointers.Type.OTHER);
      }
    }
  }

  static final class PlayerPointers extends BungeeFacet<ProxiedPlayer> implements Facet.Pointers<ProxiedPlayer> {
    PlayerPointers() {
      super(ProxiedPlayer.class);
    }

    @Override
    public void contributePointers(final ProxiedPlayer viewer, final net.kyori.adventure.pointer.Pointers.Builder builder) {
      builder.withDynamic(Identity.UUID, viewer::getUniqueId);
      builder.withDynamic(Identity.LOCALE, viewer::getLocale);
      builder.withDynamic(FacetPointers.SERVER, () -> viewer.getServer().getInfo().getName());
      builder.withStatic(FacetPointers.TYPE, FacetPointers.Type.PLAYER);
    }
  }

  static class Translator extends FacetBase<ProxyServer> implements FacetComponentFlattener.Translator<ProxyServer> {
    private static final boolean SUPPORTED;

    static {
      boolean supported;
      try {
        Class.forName("net.md_5.bungee.chat.TranslationRegistry");
        supported = true;
      } catch (final ClassNotFoundException ex) {
        supported = false;
      }

      SUPPORTED = supported;
    }

    Translator() {
      super(ProxyServer.class);
    }

    @Override
    public boolean isSupported() {
      return super.isSupported() && SUPPORTED;
    }

    @Override
    public @NonNull String valueOrDefault(final @NonNull ProxyServer game, final @NonNull String key) {
      return TranslationRegistry.INSTANCE.translate(key);
    }
  }
}
