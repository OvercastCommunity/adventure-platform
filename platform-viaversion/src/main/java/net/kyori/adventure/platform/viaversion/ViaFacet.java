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
package net.kyori.adventure.platform.viaversion;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolPipeline;
import com.viaversion.viaversion.api.protocol.packet.ClientboundPacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.packet.provider.PacketTypeMap;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.rewriter.ComponentRewriter;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonParser;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.platform.facet.Facet;
import net.kyori.adventure.platform.facet.FacetBase;
import net.kyori.adventure.platform.facet.Knob;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import net.kyori.adventure.text.serializer.json.legacyimpl.NBTLegacyHoverEventSerializer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static net.kyori.adventure.platform.facet.Knob.logError;

// Non-API
@SuppressWarnings({"checkstyle:FilteringWriteTag", "checkstyle:MissingJavadocType", "checkstyle:MissingJavadocMethod"})
public class ViaFacet<V> extends FacetBase<V> implements Facet.Message<V, String> {
  private static final String PACKAGE = "com.viaversion.viaversion";
  private static final int SUPPORTED_VIA_MAJOR_VERSION = 5;
  private static final boolean SUPPORTED;
  private static final String EMPTY_COMPONENT = "{\"text\":\"\"}";

  // The component will go through the ViaVersion pipeline starting from Minecraft 1.16
  private static final int VERSION_1_16 = 2526; // 20w16a
  private static final GsonComponentSerializer GSON_SERIALIZER_1_16 = GsonComponentSerializer.builder()
          .options(JSONOptions.byDataVersion().at(VERSION_1_16))
          .build();
  private static final GsonComponentSerializer GSON_SERIALIZER_PRE_1_16 = GsonComponentSerializer.colorDownsamplingGson()
          .toBuilder()
          .legacyHoverEventSerializer(NBTLegacyHoverEventSerializer.get())
          .build();

  static {
    boolean supported = false;
    try {
      // Check if the ViaVersion API is present and is a supported major version
      Class.forName(PACKAGE + ".api.ViaAPI").getDeclaredMethod("majorVersion");
      supported = Via.getAPI().majorVersion() == SUPPORTED_VIA_MAJOR_VERSION;
    } catch (final Throwable ignored) {
    }
    SUPPORTED = supported && Knob.isEnabled("viaversion", true);
  }

  private final Function<V, UserConnection> connectionFunction;
  private final ProtocolVersion hexColorProtocol;
  private final @Nullable ProtocolVersion minProtocol;
  protected final @NonNull Class<? extends V> viaViewerClass;

  public ViaFacet(final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction, final String minProtocol) {
    super(viewerClass);
    this.viaViewerClass = viewerClass;
    this.connectionFunction = connectionFunction;
    this.hexColorProtocol = ProtocolVersion.getClosest(VERSION_HEX_COLOR);
    this.minProtocol = ProtocolVersion.getClosest(minProtocol);
  }

  @Override
  public boolean isSupported() {
    return super.isSupported()
      && SUPPORTED
      && this.connectionFunction != null
      && this.minProtocol != null
      && this.minProtocol.isKnown();
  }

  @Override
  public boolean isApplicable(final @NonNull V viewer) {
    if (!super.isApplicable(viewer)
      || this.minProtocol == null
      || !this.minProtocol.newerThan(Via.getAPI().getServerVersion().lowestSupportedProtocolVersion())) {
      return false;
    }

    final ProtocolVersion protocol = this.findProtocol(viewer);
    return protocol.isKnown() && protocol.newerThanOrEqualTo(this.minProtocol);
  }

  public @Nullable UserConnection findConnection(final @NonNull V viewer) {
    return this.connectionFunction.apply(viewer);
  }

  public ProtocolVersion findProtocol(final @NonNull V viewer) {
    return protocolOf(this.findConnection(viewer));
  }

  public static ProtocolVersion protocolOf(final @Nullable UserConnection connection) {
    return connection == null ? ProtocolVersion.unknown : connection.getProtocolInfo().protocolVersion();
  }

  public boolean supportsHexColor(final @Nullable UserConnection connection) {
    final ProtocolVersion protocol = protocolOf(connection);
    return protocol.isKnown() && protocol.newerThanOrEqualTo(this.hexColorProtocol);
  }

  @NonNull
  @Override
  public String createMessage(final @NonNull V viewer, final @NonNull Component message) {
    return this.createMessage(this.findConnection(viewer), message);
  }

  private @NonNull String createMessage(final @Nullable UserConnection connection, final @NonNull Component message) {
    return (this.supportsHexColor(connection) ? GSON_SERIALIZER_1_16 : GSON_SERIALIZER_PRE_1_16).serialize(message);
  }

  public static class ProtocolBased<V> extends ViaFacet<V> {
    private final Class<? extends Protocol<?, ?, ?, ?>> protocolClass;
    private final ClientboundPacketType packetType;

    @SuppressWarnings("unchecked")
    protected ProtocolBased(final @NonNull String fromProtocol, final @NonNull String toProtocol, final String minProtocol, final @NonNull String packetName, final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction) {
      super(viewerClass, connectionFunction, minProtocol);

      final String protocolClassName = MessageFormat.format("{0}.protocols.v{1}to{2}.Protocol{1}To{2}", PACKAGE, fromProtocol, toProtocol);

      Class<? extends Protocol<?, ?, ?, ?>> protocolClass = null;
      ClientboundPacketType packetType = null;
      try {
        protocolClass = (Class<? extends Protocol<?, ?, ?, ?>>) Class.forName(protocolClassName);
        Protocol<?, ?, ?, ?> protocol = Via.getManager().getProtocolManager().getProtocol(protocolClass);
        if (protocol == null) {
          protocol = protocolClass.getDeclaredConstructor().newInstance();
        }
        final Map<State, ? extends PacketTypeMap<? extends ClientboundPacketType>> packetTypes = protocol.getPacketTypesProvider().mappedClientboundPacketTypes();
        final PacketTypeMap<? extends ClientboundPacketType> playPacketTypes = packetTypes.get(State.PLAY);
        if (playPacketTypes != null) {
          packetType = playPacketTypes.typeByName(packetName);
        }
      } catch (final Throwable ignored) {
        // No-op, ViaVersion is not loaded
      }

      this.protocolClass = protocolClass;
      this.packetType = packetType;
    }

    @Override
    public boolean isSupported() {
      return super.isSupported()
        && this.protocolClass != null
        && this.packetType != null;
    }

    public PacketWrapper createPacket(final @NonNull V viewer) {
      return PacketWrapper.create(this.packetType, this.findConnection(viewer));
    }

    public void sendPacket(final @NonNull PacketWrapper packet) {
      if (packet.user() == null) return;
      try {
        packet.scheduleSend(this.protocolClass);
      } catch (final Throwable error) {
        logError(error, "Failed to send ViaVersion packet: %s %s", packet.user(), packet);
      }
    }

    protected void writeComponent(final @NonNull PacketWrapper packet, final @Nullable String message) {
      packet.write(Types.COMPONENT, this.parse(packet.user(), message));
    }

    public @NonNull JsonElement parse(final @Nullable UserConnection connection, final @Nullable String message) {
      final JsonElement element = JsonParser.parseString(message == null ? EMPTY_COMPONENT : message);
      this.rewriteComponent(connection, element);
      return element;
    }

    private void rewriteComponent(final @Nullable UserConnection connection, final @NonNull JsonElement element) {
      if (connection == null) return;

      final ProtocolPipeline pipeline = connection.getProtocolInfo().getPipeline();
      if (!pipeline.contains(this.protocolClass)) return;

      for (final Protocol<?, ?, ?, ?> protocol : pipeline.reversedPipes()) {
        try {
          final ComponentRewriter rewriter = protocol.getComponentRewriter();
          if (rewriter != null) {
            rewriter.processText(connection, element);
          }

          Protocol1_12Rewriters.processText(protocol.getClass(), connection, element);
        } catch (final Throwable error) {
          logError(error, "Failed to rewrite component for protocol: %s %s", protocol, element);
        }

        if (protocol.getClass() == this.protocolClass) break;
      }
    }
  }

  // Most component rewriters are reachable through Protocol#getComponentRewriter(), but Protocol1_11_1To1_12
  // applies its own from classes outside the API. Look those up reflectively so a component is rewritten by
  // every protocol we inject past.
  private static final class Protocol1_12Rewriters {
    private static final @Nullable Class<?> PROTOCOL = findClass("v1_11_1to1_12.Protocol1_11_1To1_12");
    // Achievement and statistic keys, which 1.12 replaced with inlined text
    private static final @Nullable Method TRANSLATE = findMethod("v1_11_1to1_12.data.TranslateRewriter", "toClient", UserConnection.class, JsonElement.class);
    // Item ids within show_item hover events, which 1.12 moved from legacy NBT to SNBT
    private static final @Nullable Method CHAT_ITEM = findMethod("v1_11_1to1_12.data.ChatItemRewriter", "toClient", JsonElement.class);

    private static void processText(final @NonNull Class<?> protocol, final @NonNull UserConnection connection, final @NonNull JsonElement element) throws ReflectiveOperationException {
      if (protocol != PROTOCOL) return;

      // Applied in the order Protocol1_11_1To1_12 itself applies them to chat
      if (TRANSLATE != null) TRANSLATE.invoke(null, connection, element);
      if (CHAT_ITEM != null) CHAT_ITEM.invoke(null, element);
    }

    private static @Nullable Class<?> findClass(final @NonNull String name) {
      try {
        return Class.forName(PACKAGE + ".protocols." + name);
      } catch (final Throwable ignored) {
        return null;
      }
    }

    private static @Nullable Method findMethod(final @NonNull String className, final @NonNull String methodName, final Class<?>... parameters) {
      final Class<?> owner = findClass(className);
      if (owner == null) return null;

      try {
        return owner.getMethod(methodName, parameters);
      } catch (final Throwable ignored) {
        return null;
      }
    }
  }

  public static class Chat<V> extends ProtocolBased<V> implements Facet.Chat<V, String> {
    protected static final byte TYPE_CHAT = 0;
    protected static final byte TYPE_SYSTEM = 1;
    protected static final byte TYPE_ACTION_BAR = 2;

    public Chat(final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction) {
      super("1_15_2", "1_16", VERSION_HEX_COLOR, "CHAT", viewerClass, connectionFunction);
    }

    @Override
    public void sendMessage(final @NonNull V viewer, final @NonNull String message) {
      this.sendMessage(viewer, message, TYPE_SYSTEM, Identity.nil().uuid());
    }

    @Override
    public void sendMessage(final @NonNull V viewer, final @NonNull String message, final ChatType.@NonNull Bound boundChatType) {
      this.sendMessage(viewer, message, TYPE_CHAT, Identity.nil().uuid());
    }

    @Override
    public void sendMessage(final @NonNull V viewer, final @NonNull String message, final @NonNull SignedMessage signedMessage, final ChatType.@NonNull Bound boundChatType) {
      this.sendMessage(viewer, message, TYPE_CHAT, signedMessage.identity().uuid());
    }

    protected void sendMessage(final @NonNull V viewer, final @NonNull String message, final byte type, final @NonNull UUID source) {
      final PacketWrapper packet = this.createPacket(viewer);
      this.writeComponent(packet, message);
      packet.write(Types.BYTE, type);
      packet.write(Types.UUID, source);
      this.sendPacket(packet);
    }
  }

  public static class ActionBar<V> extends ProtocolBased<V> implements Facet.ActionBar<V, String> {
    public ActionBar(final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction) {
      super("1_15_2", "1_16", VERSION_HEX_COLOR, "CHAT", viewerClass, connectionFunction);
    }

    @Override
    public void sendMessage(final @NonNull V viewer, final @NonNull String message) {
      final PacketWrapper packet = this.createPacket(viewer);
      this.writeComponent(packet, message);
      packet.write(Types.BYTE, ViaFacet.Chat.TYPE_ACTION_BAR);
      packet.write(Types.UUID, Identity.nil().uuid());
      this.sendPacket(packet);
    }
  }

  public static class ActionBarTitle<V> extends ProtocolBased<V> implements Facet.ActionBar<V, String> {
    public ActionBarTitle(final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction) {
      super("1_10", "1_11", TitlePacket.VERSION_ACTION_BAR, "SET_TITLES", viewerClass, connectionFunction);
    }

    @Override
    public void sendMessage(final @NonNull V viewer, final @NonNull String message) {
      final PacketWrapper packet = this.createPacket(viewer);
      packet.write(Types.VAR_INT, TitlePacket.ACTION_ACTIONBAR);
      this.writeComponent(packet, message);
      this.sendPacket(packet);
    }
  }

  public static class Title<V> extends ProtocolBased<V> implements Facet.TitlePacket<V, String, List<Consumer<PacketWrapper>>, Consumer<V>> {
    protected Title(final @NonNull String fromProtocol, final @NonNull String toProtocol, final String minProtocol, final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction) {
      super(fromProtocol, toProtocol, minProtocol, "SET_TITLES", viewerClass, connectionFunction);
    }

    public Title(final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction) {
      this("1_15_2", "1_16", VERSION_HEX_COLOR, viewerClass, connectionFunction);
    }

    @Override
    public @NonNull List<Consumer<PacketWrapper>> createTitleCollection() {
      return new ArrayList<>();
    }

    @Override
    public void contributeTitle(final @NonNull List<Consumer<PacketWrapper>> coll, final @NonNull String title) {
      coll.add(packet -> {
        packet.write(Types.VAR_INT, ACTION_TITLE);
        this.writeComponent(packet, title);
      });
    }

    @Override
    public void contributeSubtitle(final @NonNull List<Consumer<PacketWrapper>> coll, final @NonNull String subtitle) {
      coll.add(packet -> {
        packet.write(Types.VAR_INT, ACTION_SUBTITLE);
        this.writeComponent(packet, subtitle);
      });
    }

    @Override
    public void contributeTimes(final @NonNull List<Consumer<PacketWrapper>> coll, final int inTicks, final int stayTicks, final int outTicks) {
      coll.add(packet -> {
        packet.write(Types.VAR_INT, ACTION_TIMES);
        packet.write(Types.INT, inTicks);
        packet.write(Types.INT, stayTicks);
        packet.write(Types.INT, outTicks);
      });
    }

    @Override
    public @Nullable Consumer<V> completeTitle(final @NonNull List<Consumer<PacketWrapper>> coll) {
      return v -> {
        for (final Consumer<PacketWrapper> packetWrapperConsumer : coll) {
          final PacketWrapper pkt = this.createPacket(v);
          packetWrapperConsumer.accept(pkt);
          this.sendPacket(pkt);
        }
      };
    }

    @Override
    public void showTitle(final @NonNull V viewer, final @NonNull Consumer<V> title) {
      title.accept(viewer);
    }

    @Override
    public void clearTitle(final @NonNull V viewer) {
      final PacketWrapper packet = this.createPacket(viewer);
      packet.write(Types.VAR_INT, ACTION_CLEAR);
      this.sendPacket(packet);
    }

    @Override
    public void resetTitle(final @NonNull V viewer) {
      final PacketWrapper packet = this.createPacket(viewer);
      packet.write(Types.VAR_INT, ACTION_RESET);
      this.sendPacket(packet);
    }
  }

  public static final class BossBar<V> extends ProtocolBased<V> implements Facet.BossBarPacket<V> {
    private final Set<V> viewers;
    private UUID id;
    private Component title = Component.empty();
    private @Nullable String hexColorTitle;
    private @Nullable String downsampledTitle;
    private float health;
    private int color;
    private int overlay;
    private byte flags;

    private BossBar(final @NonNull String fromProtocol, final @NonNull String toProtocol, final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction, final Collection<V> viewers) {
      super(fromProtocol, toProtocol, VERSION_BOSS_BAR, "BOSS_EVENT", viewerClass, connectionFunction);
      this.viewers = new CopyOnWriteArraySet<>(viewers);
    }

    public static class Builder<V> extends ViaFacet<V> implements Facet.BossBar.Builder<V, Facet.BossBar<V>> {
      public Builder(final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction) {
        super(viewerClass, connectionFunction, VERSION_HEX_COLOR);
      }

      @Override
      public Facet.@NonNull BossBar<V> createBossBar(final @NonNull Collection<V> viewer) {
        return new ViaFacet.BossBar<>("1_15_2", "1_16", this.viaViewerClass, this::findConnection, viewer);
      }
    }

    public static class Builder1_9_To_1_15<V> extends ViaFacet<V> implements Facet.BossBar.Builder<V, Facet.BossBar<V>> {
      public Builder1_9_To_1_15(final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> connectionFunction) {
        super(viewerClass, connectionFunction, VERSION_BOSS_BAR);
      }

      @Override
      public Facet.@NonNull BossBar<V> createBossBar(final @NonNull Collection<V> viewer) {
        return new ViaFacet.BossBar<>("1_8", "1_9", this.viaViewerClass, this::findConnection, viewer);
      }
    }

    @Override
    public void bossBarInitialized(final net.kyori.adventure.bossbar.@NonNull BossBar bar) {
      Facet.BossBarPacket.super.bossBarInitialized(bar);
      this.id = UUID.randomUUID();
      this.broadcastPacket(ACTION_ADD);
    }

    @Override
    public void bossBarNameChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final @NonNull Component oldName, final @NonNull Component newName) {
      this.title = newName;
      this.hexColorTitle = null;
      this.downsampledTitle = null;
      this.broadcastPacket(ACTION_TITLE);
    }

    private @NonNull String createTitle(final @Nullable UserConnection connection) {
      if (this.supportsHexColor(connection)) {
        if (this.hexColorTitle == null) this.hexColorTitle = GSON_SERIALIZER_1_16.serialize(this.title);
        return this.hexColorTitle;
      }
      if (this.downsampledTitle == null) this.downsampledTitle = GSON_SERIALIZER_PRE_1_16.serialize(this.title);
      return this.downsampledTitle;
    }

    @Override
    public void bossBarProgressChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final float oldPercent, final float newPercent) {
      this.health = newPercent;
      this.broadcastPacket(ACTION_HEALTH);
    }

    @Override
    public void bossBarColorChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final net.kyori.adventure.bossbar.BossBar.@NonNull Color oldColor, final net.kyori.adventure.bossbar.BossBar.@NonNull Color newColor) {
      this.color = this.createColor(newColor);
      this.broadcastPacket(ACTION_STYLE);
    }

    @Override
    public void bossBarOverlayChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final net.kyori.adventure.bossbar.BossBar.@NonNull Overlay oldOverlay, final net.kyori.adventure.bossbar.BossBar.@NonNull Overlay newOverlay) {
      this.overlay = this.createOverlay(newOverlay);
      this.broadcastPacket(ACTION_STYLE);
    }

    @Override
    public void bossBarFlagsChanged(final net.kyori.adventure.bossbar.@NonNull BossBar bar, final @NonNull Set<net.kyori.adventure.bossbar.BossBar.Flag> flagsAdded, final @NonNull Set<net.kyori.adventure.bossbar.BossBar.Flag> flagsRemoved) {
      this.flags = this.createFlag(this.flags, flagsAdded, flagsRemoved);
      this.broadcastPacket(ACTION_FLAG);
    }

    public void sendPacket(final @NonNull V viewer, final int action) {
      final PacketWrapper packet = this.createPacket(viewer);
      packet.write(Types.UUID, this.id);
      packet.write(Types.VAR_INT, action);
      if (action == ACTION_ADD || action == ACTION_TITLE) {
        this.writeComponent(packet, this.createTitle(packet.user()));
      }
      if (action == ACTION_ADD || action == ACTION_HEALTH) {
        packet.write(Types.FLOAT, this.health);
      }
      if (action == ACTION_ADD || action == ACTION_STYLE) {
        packet.write(Types.VAR_INT, this.color);
        packet.write(Types.VAR_INT, this.overlay);
      }
      if (action == ACTION_ADD || action == ACTION_FLAG) {
        packet.write(Types.BYTE, this.flags);
      }
      this.sendPacket(packet);
    }

    public void broadcastPacket(final int action) {
      if (this.isEmpty()) return;
      for (final V viewer : this.viewers) {
        this.sendPacket(viewer, action);
      }
    }

    @Override
    public void addViewer(final @NonNull V viewer) {
      if (this.viewers.add(viewer)) {
        this.sendPacket(viewer, ACTION_ADD);
      }
    }

    @Override
    public void removeViewer(final @NonNull V viewer) {
      if (this.viewers.remove(viewer)) {
        this.sendPacket(viewer, ACTION_REMOVE);
      }
    }

    @Override
    public boolean isEmpty() {
      return this.id == null || this.viewers.isEmpty();
    }

    @Override
    public void close() {
      this.broadcastPacket(ACTION_REMOVE);
      this.viewers.clear();
    }
  }

  public static final class TabList<V> extends ProtocolBased<V> implements Facet.TabList<V, String> {

    public TabList(final @NonNull Class<? extends V> viewerClass, final @NonNull Function<V, UserConnection> userConnection) {
      super("1_15_2", "1_16", VERSION_HEX_COLOR, "TAB_LIST", viewerClass, userConnection);
    }

    @Override
    public void send(final V viewer, final @Nullable String header, final @Nullable String footer) {
      final PacketWrapper packet = this.createPacket(viewer);
      this.writeComponent(packet, header);
      this.writeComponent(packet, footer);
      this.sendPacket(packet);
    }
  }
}
