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
package net.kyori.adventure.platform.bukkit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static java.lang.invoke.MethodHandles.insertArguments;
import static net.kyori.adventure.platform.bukkit.BukkitComponentSerializer.gson;
import static net.kyori.adventure.platform.bukkit.MinecraftReflection.findClass;
import static net.kyori.adventure.platform.bukkit.MinecraftReflection.findCraftClass;
import static net.kyori.adventure.platform.bukkit.MinecraftReflection.findMcClassName;
import static net.kyori.adventure.platform.bukkit.MinecraftReflection.findMethod;
import static net.kyori.adventure.platform.bukkit.MinecraftReflection.findNmsClass;
import static net.kyori.adventure.platform.bukkit.MinecraftReflection.findNmsClassName;
import static net.kyori.adventure.platform.bukkit.MinecraftReflection.findStaticMethod;
import static net.kyori.adventure.platform.bukkit.MinecraftReflection.lookup;

/**
 * A component serializer for {@code net.minecraft.server.<version>.IChatBaseComponent}.
 *
 * <p>Due to Bukkit version namespaces, the return type does not reflect the actual type.</p>
 *
 * <p>Color downsampling will be performed as necessary for the running server version.</p>
 *
 * <p>If not {@link #isSupported()}, an {@link UnsupportedOperationException} will be thrown on any serialize or deserialize operations.</p>
 *
 * @see #get()
 * @since 4.0.0
 */
@ApiStatus.Experimental // due to direct server implementation references
public final class MinecraftComponentSerializer implements ComponentSerializer<Component, Component, Object> {
  private static final MinecraftComponentSerializer INSTANCE = new MinecraftComponentSerializer();

  /**
   * Gets whether this serializer is supported.
   *
   * @return if the serializer is supported.
   * @since 4.0.0
   */
  public static boolean isSupported() {
    return SUPPORTED;
  }

  /**
   * Gets the component serializer.
   *
   * @return a component serializer
   * @since 4.0.0
   */
  public static @NonNull MinecraftComponentSerializer get() {
    return INSTANCE;
  }

  private static final @Nullable Class<?> CLASS_JSON_DESERIALIZER = findClass("com.goo".concat("gle.gson.JsonDeserializer")); // Hide from relocation checkers
  private static final @Nullable Class<?> CLASS_JSON_ELEMENT = findClass("com.goo".concat("gle.gson.JsonElement"));
  private static final @Nullable Class<?> CLASS_JSON_OPS = findClass("com.mo".concat("jang.serialization.JsonOps"));
  private static final @Nullable Class<?> CLASS_JSON_PARSER = findClass("com.goo".concat("gle.gson.JsonParser"));
  private static final @Nullable Class<?> CLASS_CHAT_COMPONENT = findClass(
    findNmsClassName("IChatBaseComponent"),
    findMcClassName("network.chat.IChatBaseComponent"),
    findMcClassName("network.chat.Component")
  );
  private static final @Nullable Class<?> CLASS_COMPONENT_SERIALIZATION = findClass(findMcClassName("network.chat.ComponentSerialization"));
  private static final @Nullable Class<?> CLASS_CRAFT_REGISTRY = findCraftClass("CraftRegistry");
  private static final @Nullable Class<?> CLASS_HOLDERLOOKUP_PROVIDER = findClass(
    findMcClassName("core.HolderLookup$Provider"), // Paper mapping
    findMcClassName("core.HolderLookup$a") // Spigot mapping
  );
  private static final @Nullable Class<?> CLASS_REGISTRY_ACCESS = findClass(
    findMcClassName("core.IRegistryCustom"),
    findMcClassName("core.RegistryAccess")
  );
  private static final @Nullable MethodHandle PARSE_JSON = findMethod(CLASS_JSON_PARSER, "parse", CLASS_JSON_ELEMENT, String.class);
  private static final @Nullable MethodHandle GET_REGISTRY = findStaticMethod(CLASS_CRAFT_REGISTRY, "getMinecraftRegistry", CLASS_REGISTRY_ACCESS);
  private static final AtomicReference<RuntimeException> INITIALIZATION_ERROR = new AtomicReference<>(new UnsupportedOperationException());
  private static final @Nullable Object JSON_OPS_INSTANCE;
  private static final @Nullable Object JSON_PARSER_INSTANCE;
  private static final @Nullable Object MC_TEXT_GSON;
  private static final @Nullable Object REGISTRY_ACCESS;
  private static final @Nullable MethodHandle TEXT_SERIALIZER_DESERIALIZE;
  private static final @Nullable MethodHandle TEXT_SERIALIZER_SERIALIZE;
  private static final @Nullable MethodHandle TEXT_SERIALIZER_DESERIALIZE_TREE;
  private static final @Nullable MethodHandle TEXT_SERIALIZER_SERIALIZE_TREE;
  private static final @Nullable MethodHandle COMPONENTSERIALIZATION_CODEC_ENCODE;
  private static final @Nullable MethodHandle COMPONENTSERIALIZATION_CODEC_DECODE;
  private static final @Nullable MethodHandle CREATE_SERIALIZATION_CONTEXT;

  private static final class InitializationState {
    @Nullable Object gson;
    @Nullable Object jsonOpsInstance;
    @Nullable Object jsonParserInstance;
    @Nullable Object registryAccessInstance;
    @Nullable MethodHandle textSerializerDeserialize;
    @Nullable MethodHandle textSerializerSerialize;
    @Nullable MethodHandle textSerializerDeserializeTree;
    @Nullable MethodHandle textSerializerSerializeTree;
    @Nullable MethodHandle codecEncode;
    @Nullable MethodHandle codecDecode;
    @Nullable MethodHandle createContext;
  }

  private static InitializationState initialize() {
    final InitializationState state = new InitializationState();
    try {
      initializeJson(state);
      final Class<?> chatComponentClass = CLASS_CHAT_COMPONENT;
      if (chatComponentClass != null) {
        initializeChat(state, chatComponentClass);
      }
    } catch (final Throwable error) {
      INITIALIZATION_ERROR.set(new UnsupportedOperationException("Error occurred during initialization", error));
    }

    return state;
  }

  private static void initializeJson(final InitializationState state) throws ReflectiveOperationException {
    if (CLASS_JSON_OPS != null) {
      final Field instanceField = CLASS_JSON_OPS.getField("INSTANCE");
      instanceField.setAccessible(true);
      state.jsonOpsInstance = instanceField.get(null);
    }
    if (CLASS_JSON_PARSER != null) {
      state.jsonParserInstance = CLASS_JSON_PARSER.getDeclaredConstructor().newInstance();
    }
  }

  private static void initializeChat(final InitializationState state, final Class<?> chatComponentClass) throws Throwable {
    final Object registryAccess = GET_REGISTRY != null ? GET_REGISTRY.invoke() : null;
    state.registryAccessInstance = registryAccess;

    final Class<?> chatSerializerClass = findChatSerializerClass(chatComponentClass);
    state.gson = findGson(chatSerializerClass);
    initializeSerializerMethods(state, chatComponentClass, registryAccess, chatSerializerClass);
    state.createContext = findSerializationContext(registryAccess);
    initializeCodecMethods(state);
  }

  private static @Nullable Class<?> findChatSerializerClass(final Class<?> chatComponentClass) {
    return Arrays.stream(chatComponentClass.getClasses())
      .filter(MinecraftComponentSerializer::isJsonDeserializer)
      .findAny()
      .orElse(findNmsClass("ChatSerializer")); // 1.7.10 compat
  }

  private static boolean isJsonDeserializer(final Class<?> candidate) {
    if (CLASS_JSON_DESERIALIZER != null) {
      return CLASS_JSON_DESERIALIZER.isAssignableFrom(candidate);
    }
    for (final Class<?> itf : candidate.getInterfaces()) {
      if (itf.getSimpleName().equals("JsonDeserializer")) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable Object findGson(final @Nullable Class<?> chatSerializerClass) throws IllegalAccessException {
    if (chatSerializerClass == null) return null;

    final Field gsonField = Arrays.stream(chatSerializerClass.getDeclaredFields())
      .filter(m -> Modifier.isStatic(m.getModifiers()))
      .filter(m -> m.getType().equals(Gson.class))
      .findFirst()
      .orElse(null);
    if (gsonField == null) return null;

    gsonField.setAccessible(true);
    return gsonField.get(null);
  }

  private static void initializeSerializerMethods(final InitializationState state, final Class<?> chatComponentClass, final @Nullable Object registryAccess, final @Nullable Class<?> chatSerializerClass) throws IllegalAccessException {
    final List<Class<?>> candidates = new ArrayList<>();
    if (chatSerializerClass != null) {
      candidates.add(chatSerializerClass);
    }
    candidates.addAll(Arrays.asList(chatComponentClass.getClasses()));

    for (final Class<?> serializerClass : candidates) {
      initializeSerializerMethods(state, chatComponentClass, registryAccess, serializerClass.getDeclaredMethods());
    }
  }

  private static void initializeSerializerMethods(final InitializationState state, final Class<?> chatComponentClass, final @Nullable Object registryAccess, final Method[] declaredMethods) throws IllegalAccessException {
    final Method deserialize = findDeserializeMethod(chatComponentClass, declaredMethods);
    final Method serialize = findSerializeMethod(chatComponentClass, declaredMethods);
    final Method deserializeTree = findDeserializeTreeMethod(chatComponentClass, declaredMethods);
    final Method serializeTree = findSerializeTreeMethod(chatComponentClass, declaredMethods);
    final Method deserializeTreeWithRegistryAccess = findDeserializeTreeWithRegistryAccessMethod(chatComponentClass, declaredMethods, registryAccess);
    final Method serializeTreeWithRegistryAccess = findSerializeTreeWithRegistryAccessMethod(chatComponentClass, declaredMethods, registryAccess);

    if (deserialize != null) {
      state.textSerializerDeserialize = lookup().unreflect(deserialize);
    }
    if (serialize != null) {
      state.textSerializerSerialize = lookup().unreflect(serialize);
    }
    if (deserializeTree != null) {
      state.textSerializerDeserializeTree = lookup().unreflect(deserializeTree);
    } else if (deserializeTreeWithRegistryAccess != null) {
      deserializeTreeWithRegistryAccess.setAccessible(true);
      state.textSerializerDeserializeTree = insertArguments(lookup().unreflect(deserializeTreeWithRegistryAccess), 1, registryAccess);
    }
    if (serializeTree != null) {
      state.textSerializerSerializeTree = lookup().unreflect(serializeTree);
    } else if (serializeTreeWithRegistryAccess != null) {
      serializeTreeWithRegistryAccess.setAccessible(true);
      state.textSerializerSerializeTree = insertArguments(lookup().unreflect(serializeTreeWithRegistryAccess), 1, registryAccess);
    }
  }

  private static @Nullable Method findDeserializeMethod(final Class<?> chatComponentClass, final Method[] methods) {
    return Arrays.stream(methods)
      .filter(m -> Modifier.isStatic(m.getModifiers()))
      .filter(m -> chatComponentClass.isAssignableFrom(m.getReturnType()))
      .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(String.class))
      .min(Comparator.comparing(Method::getName)) // prefer the #a method
      .orElse(null);
  }

  private static @Nullable Method findSerializeMethod(final Class<?> chatComponentClass, final Method[] methods) {
    return Arrays.stream(methods)
      .filter(m -> Modifier.isStatic(m.getModifiers()))
      .filter(m -> m.getReturnType().equals(String.class))
      .filter(m -> m.getParameterCount() == 1 && chatComponentClass.isAssignableFrom(m.getParameterTypes()[0]))
      .findFirst()
      .orElse(null);
  }

  private static @Nullable Method findDeserializeTreeMethod(final Class<?> chatComponentClass, final Method[] methods) {
    return Arrays.stream(methods)
      .filter(m -> Modifier.isStatic(m.getModifiers()))
      .filter(m -> chatComponentClass.isAssignableFrom(m.getReturnType()))
      .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(CLASS_JSON_ELEMENT))
      .findFirst()
      .orElse(null);
  }

  private static @Nullable Method findSerializeTreeMethod(final Class<?> chatComponentClass, final Method[] methods) {
    return Arrays.stream(methods)
      .filter(m -> Modifier.isStatic(m.getModifiers()))
      .filter(m -> m.getReturnType().equals(CLASS_JSON_ELEMENT))
      .filter(m -> m.getParameterCount() == 1 && chatComponentClass.isAssignableFrom(m.getParameterTypes()[0]))
      .findFirst()
      .orElse(null);
  }

  private static @Nullable Method findDeserializeTreeWithRegistryAccessMethod(final Class<?> chatComponentClass, final Method[] methods, final @Nullable Object registryAccess) {
    return Arrays.stream(methods)
      .filter(m -> Modifier.isStatic(m.getModifiers()))
      .filter(m -> chatComponentClass.isAssignableFrom(m.getReturnType()))
      .filter(m -> m.getParameterCount() == 2)
      .filter(m -> m.getParameterTypes()[0].equals(CLASS_JSON_ELEMENT))
      .filter(m -> m.getParameterTypes()[1].isInstance(registryAccess))
      .findFirst()
      .orElse(null);
  }

  private static @Nullable Method findSerializeTreeWithRegistryAccessMethod(final Class<?> chatComponentClass, final Method[] methods, final @Nullable Object registryAccess) {
    return Arrays.stream(methods)
      .filter(m -> Modifier.isStatic(m.getModifiers()))
      .filter(m -> m.getReturnType().equals(CLASS_JSON_ELEMENT))
      .filter(m -> m.getParameterCount() == 2)
      .filter(m -> chatComponentClass.isAssignableFrom(m.getParameterTypes()[0]))
      .filter(m -> m.getParameterTypes()[1].isInstance(registryAccess))
      .findFirst()
      .orElse(null);
  }

  private static @Nullable MethodHandle findSerializationContext(final @Nullable Object registryAccess) throws IllegalAccessException {
    if (registryAccess == null || CLASS_HOLDERLOOKUP_PROVIDER == null) return null;

    for (final Method m : CLASS_HOLDERLOOKUP_PROVIDER.getDeclaredMethods()) {
      m.setAccessible(true);
      if (m.getParameterCount() == 1 && m.getParameterTypes()[0].getSimpleName().equals("DynamicOps") && m.getReturnType().getSimpleName().contains("RegistryOps")) {
        return lookup().unreflect(m);
      }
    }
    return null;
  }

  private static void initializeCodecMethods(final InitializationState state) throws IllegalAccessException {
    if (CLASS_COMPONENT_SERIALIZATION == null) return;

    for (final Field f : CLASS_COMPONENT_SERIALIZATION.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers()) && f.getType().getSimpleName().equals("Codec")) {
        initializeCodecMethods(state, f);
        return;
      }
    }
  }

  private static void initializeCodecMethods(final InitializationState state, final Field codecField) throws IllegalAccessException {
    codecField.setAccessible(true);
    final Object codecInstance = codecField.get(null);
    final Class<?> codecClass = codecInstance.getClass();
    for (final Method m : codecClass.getDeclaredMethods()) {
      if (m.getName().equals("decode")) {
        state.codecDecode = lookup().unreflect(m).bindTo(codecInstance);
      } else if (m.getName().equals("encode")) {
        state.codecEncode = lookup().unreflect(m).bindTo(codecInstance);
      }
    }
  }

  static {
    final InitializationState state = initialize();
    MC_TEXT_GSON = state.gson;
    JSON_OPS_INSTANCE = state.jsonOpsInstance;
    JSON_PARSER_INSTANCE = state.jsonParserInstance;
    TEXT_SERIALIZER_DESERIALIZE = state.textSerializerDeserialize;
    TEXT_SERIALIZER_SERIALIZE = state.textSerializerSerialize;
    TEXT_SERIALIZER_DESERIALIZE_TREE = state.textSerializerDeserializeTree;
    TEXT_SERIALIZER_SERIALIZE_TREE = state.textSerializerSerializeTree;
    COMPONENTSERIALIZATION_CODEC_ENCODE = state.codecEncode;
    COMPONENTSERIALIZATION_CODEC_DECODE = state.codecDecode;
    CREATE_SERIALIZATION_CONTEXT = state.createContext;
    REGISTRY_ACCESS = state.registryAccessInstance;
  }

  private static final boolean SUPPORTED = MC_TEXT_GSON != null || (TEXT_SERIALIZER_DESERIALIZE != null && TEXT_SERIALIZER_SERIALIZE != null) || (TEXT_SERIALIZER_DESERIALIZE_TREE != null && TEXT_SERIALIZER_SERIALIZE_TREE != null) || (COMPONENTSERIALIZATION_CODEC_ENCODE != null && COMPONENTSERIALIZATION_CODEC_DECODE != null && CREATE_SERIALIZATION_CONTEXT != null && JSON_OPS_INSTANCE != null);

  @Override
  public @NonNull Component deserialize(final @NonNull Object input) {
    if (!SUPPORTED) throw INITIALIZATION_ERROR.get();

    try {
      final Object element;
      if (TEXT_SERIALIZER_SERIALIZE_TREE != null) {
        element = TEXT_SERIALIZER_SERIALIZE_TREE.invoke(input);
      } else if (MC_TEXT_GSON != null) {
        element = ((Gson) MC_TEXT_GSON).toJsonTree(input);
      } else if (COMPONENTSERIALIZATION_CODEC_ENCODE != null && CREATE_SERIALIZATION_CONTEXT != null) {
        final Object serializationContext = CREATE_SERIALIZATION_CONTEXT.bindTo(REGISTRY_ACCESS).invoke(JSON_OPS_INSTANCE);
        final Object result = COMPONENTSERIALIZATION_CODEC_ENCODE.invoke(input, serializationContext, null);
        final Method getOrThrow = result.getClass().getMethod("getOrThrow", java.util.function.Function.class);
        final Object jsonElement = getOrThrow.invoke(result, (java.util.function.Function<Throwable, RuntimeException>) RuntimeException::new);
        return gson().serializer().fromJson(jsonElement.toString(), Component.class);
      } else {
        return gson().deserialize((String) TEXT_SERIALIZER_SERIALIZE.invoke(input));
      }
      return gson().serializer().fromJson(element.toString(), Component.class);
    } catch (final Throwable error) {
      throw new UnsupportedOperationException(error);
    }
  }

  @Override
  public @NonNull Object serialize(final @NonNull Component component) {
    if (!SUPPORTED) throw INITIALIZATION_ERROR.get();

    if (TEXT_SERIALIZER_DESERIALIZE_TREE != null || MC_TEXT_GSON != null) {
      final JsonElement json = gson().serializer().toJsonTree(component);
      try {
        if (TEXT_SERIALIZER_DESERIALIZE_TREE != null) {
          final Object unRelocatedJsonElement = PARSE_JSON.invoke(JSON_PARSER_INSTANCE, json.toString());
          return TEXT_SERIALIZER_DESERIALIZE_TREE.invoke(unRelocatedJsonElement);
        }
        return ((Gson) MC_TEXT_GSON).fromJson(json, CLASS_CHAT_COMPONENT);
      } catch (final Throwable error) {
        throw new UnsupportedOperationException(error);
      }
    } else {
      try {
        if (COMPONENTSERIALIZATION_CODEC_DECODE != null && CREATE_SERIALIZATION_CONTEXT != null) {
          final Object serializationContext = CREATE_SERIALIZATION_CONTEXT.bindTo(REGISTRY_ACCESS).invoke(JSON_OPS_INSTANCE);
          final Object result = COMPONENTSERIALIZATION_CODEC_DECODE.invoke(serializationContext, gson().serializeToTree(component));
          final Method getOrThrow = result.getClass().getMethod("getOrThrow", java.util.function.Function.class);
          final Object pair = getOrThrow.invoke(result, (java.util.function.Function<Throwable, RuntimeException>) RuntimeException::new);
          final Method getFirst = pair.getClass().getMethod("getFirst");
          return getFirst.invoke(pair);
        }
        return TEXT_SERIALIZER_DESERIALIZE.invoke(gson().serialize(component));
      } catch (final Throwable error) {
        throw new UnsupportedOperationException(error);
      }
    }
  }
}
