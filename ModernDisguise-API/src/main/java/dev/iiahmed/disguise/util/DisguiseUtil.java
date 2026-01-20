package dev.iiahmed.disguise.util;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import dev.iiahmed.disguise.Skin;
import dev.iiahmed.disguise.util.reflection.FieldAccessor;
import dev.iiahmed.disguise.util.reflection.Reflections;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;

@SuppressWarnings({"unchecked", "rawtypes"})
public final class DisguiseUtil {

    private static final String HANDLER_NAME = "ModernDisguise";
    public static final String PREFIX = "net.minecraft.server." + (Version.isBelow(17) ? "v" + Version.NMS + "." : "");

    /**
     * Whether GameProfile is a Java Record (authlib 7.0+, Paper 1.21.1+).
     * Records have immutable final fields that cannot be modified via reflection.
     * Detected at runtime using reflection since isRecord() is Java 16+.
     */
    public static final boolean IS_GAME_PROFILE_RECORD;

    // Reflection methods for Record-based GameProfile (authlib 7.0+)
    private static Method PROFILE_ID_METHOD;      // id() accessor
    private static Method PROFILE_NAME_ACCESSOR;  // name() accessor for Records
    private static Method PROFILE_PROPS_METHOD;   // properties() accessor
    private static Constructor<?> PROFILE_CONSTRUCTOR_3ARG; // GameProfile(UUID, String, PropertyMap)
    private static Constructor<?> PROPERTY_MAP_CONSTRUCTOR; // PropertyMap(Multimap)

    /**
     * Field reference for modifying the profile name directly (authlib 6.x and earlier).
     * Will be null if GameProfile is a Record.
     */
    public static final Field PROFILE_NAME;

    /**
     * Field for the gameProfile in net.minecraft.world.entity.player.Player.
     * Used to replace the entire GameProfile when it's a Record.
     */
    private static Field PLAYER_PROFILE_FIELD;

    public static final boolean PRIMARY, INJECTION;

    public static FieldAccessor<?> CONNECTION;
    public static FieldAccessor<?> NETWORK_MANAGER;
    public static FieldAccessor<Channel> NETWORK_CHANNEL;

    private static final Method GET_PROFILE, GET_HANDLE;
    private static final Map PLAYERS_MAP;

    static {
        final boolean obf = Version.isOrOver(17);
        Field profileName = null;

        // Check if GameProfile is a Record using reflection (isRecord() is Java 16+)
        boolean isRecord = false;
        try {
            Method isRecordMethod = Class.class.getMethod("isRecord");
            isRecord = (Boolean) isRecordMethod.invoke(GameProfile.class);
        } catch (NoSuchMethodException e) {
            // Java < 16, definitely not a Record
            isRecord = false;
        } catch (Exception e) {
            isRecord = false;
        }
        IS_GAME_PROFILE_RECORD = isRecord;

        try {
            final Class<?> craftPlayer;
            if (Version.IS_PAPER && Version.IS_20_R4_PLUS) {
                craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            } else {
                craftPlayer = Class.forName("org.bukkit.craftbukkit.v" + Version.NMS + ".entity.CraftPlayer");
            }

            GET_PROFILE = craftPlayer.getMethod("getProfile");
            GET_HANDLE = craftPlayer.getMethod("getHandle");

            // Only try to access the name field if GameProfile is NOT a Record
            if (!IS_GAME_PROFILE_RECORD) {
                profileName = GameProfile.class.getDeclaredField("name");
                profileName.setAccessible(true);
            } else {
                // For Records, set up reflection for accessor methods
                try {
                    PROFILE_ID_METHOD = GameProfile.class.getMethod("id");
                    PROFILE_NAME_ACCESSOR = GameProfile.class.getMethod("name");
                    PROFILE_PROPS_METHOD = GameProfile.class.getMethod("properties");

                    // Find the 3-arg constructor: GameProfile(UUID, String, PropertyMap)
                    PROFILE_CONSTRUCTOR_3ARG = GameProfile.class.getConstructor(UUID.class, String.class, PropertyMap.class);

                    // Find PropertyMap constructor that takes Multimap
                    PROPERTY_MAP_CONSTRUCTOR = PropertyMap.class.getConstructor(Multimap.class);

                    Bukkit.getLogger().info("[ModernDisguise] GameProfile Record reflection initialized successfully");
                } catch (Exception e) {
                    Bukkit.getLogger().log(Level.WARNING, "[ModernDisguise] Failed to initialize Record reflection methods", e);
                }

                // Find the gameProfile field in net.minecraft.world.entity.player.Player
                try {
                    final Class<?> nmsPlayerClass = Class.forName(
                            obf ? "net.minecraft.world.entity.player.Player" : PREFIX + "EntityHuman"
                    );

                    // Try to find gameProfile field
                    PLAYER_PROFILE_FIELD = findGameProfileField(nmsPlayerClass);
                    if (PLAYER_PROFILE_FIELD != null) {
                        PLAYER_PROFILE_FIELD.setAccessible(true);
                        Bukkit.getLogger().info("[ModernDisguise] Found GameProfile field: " + PLAYER_PROFILE_FIELD.getName());
                    }

                    Bukkit.getLogger().info("[ModernDisguise] GameProfile is a Record (authlib 7.0+) - using profile replacement strategy");
                } catch (ClassNotFoundException e) {
                    Bukkit.getLogger().log(Level.WARNING, "[ModernDisguise] Could not find NMS Player class for profile replacement", e);
                }
            }

            final Field listFiled = Bukkit.getServer().getClass().getDeclaredField("playerList");
            listFiled.setAccessible(true);
            final Class<?> playerListClass = Class.forName((obf ?
                    PREFIX + "players." : PREFIX)
                    + "PlayerList");
            final Object playerList = listFiled.get(Bukkit.getServer());
            final Field playersByName = playerListClass.getDeclaredField("playersByName");
            playersByName.setAccessible(true);
            PLAYERS_MAP = (Map) playersByName.get(playerList);
        } catch (final Exception exception) {
            throw new RuntimeException("Failed to load ModernDisguise's primary features", exception);
        }

        PROFILE_NAME = profileName;
        PRIMARY = true;
        boolean injection;
        try {
            final Class<?> entityPlayer = Class.forName(
                    (obf ? PREFIX + "level." : PREFIX) + "EntityPlayer"
            );
            final Class<?> playerConnection = Class.forName(
                    (obf ? PREFIX + "network." : PREFIX) + (Version.IS_20_R2_PLUS ? "ServerCommonPacketListenerImpl" : "PlayerConnection")
            );
            final Class<?> networkManager = Class.forName(
                    (obf ? "net.minecraft.network." : PREFIX) + "NetworkManager"
            );

            CONNECTION = Reflections.getField(entityPlayer, playerConnection);
            NETWORK_CHANNEL = Reflections.getField(networkManager, Channel.class);
            NETWORK_MANAGER = Reflections.getField(playerConnection, networkManager);
            injection = true;
        } catch (final Throwable exception) {
            injection = false;
            Bukkit.getServer().getLogger().log(Level.SEVERE, "Failed to load ModernDisguise's secondary features (disguising as entities)", exception);
        }

        INJECTION = injection;
    }

    /**
     * Find the gameProfile field in the NMS Player class.
     */
    private static Field findGameProfileField(Class<?> nmsPlayerClass) {
        // Try Mojang-mapped name first
        try {
            return nmsPlayerClass.getDeclaredField("gameProfile");
        } catch (NoSuchFieldException ignored) {}

        // Try alternate field names for different mappings
        for (String fieldName : new String[]{"f", "bM", "bN", "bO", "bP", "cs", "ct", "cu"}) {
            try {
                Field field = nmsPlayerClass.getDeclaredField(fieldName);
                if (GameProfile.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            } catch (NoSuchFieldException ignored) {}
        }

        // Search all fields for GameProfile type
        for (Field field : nmsPlayerClass.getDeclaredFields()) {
            if (GameProfile.class.isAssignableFrom(field.getType())) {
                return field;
            }
        }

        // Check superclass
        if (nmsPlayerClass.getSuperclass() != null) {
            return findGameProfileField(nmsPlayerClass.getSuperclass());
        }

        return null;
    }

    /**
     * @return the {@link GameProfile} of the given {@link Player}
     */
    public static GameProfile getProfile(@NotNull final Player player) {
        try {
            return (GameProfile) GET_PROFILE.invoke(player);
        } catch (final Exception ignored) {
            return null;
        }
    }

    /**
     * Finds an online player by their name.
     *
     * @param name the name of the player to look for, must not be null
     * @return the player with the specified name, or null if not found
     */
    @Nullable
    public static Player getPlayer(@NotNull final String name) {
        final Player direct = Bukkit.getPlayerExact(name);
        if (direct != null) {
            return direct;
        }

        final String lowercase = name.toLowerCase(Locale.ENGLISH);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ENGLISH).equals(lowercase)) return player;
        }
        return null;
    }

    /**
     * Registers a name as an online player to disallow {@link Player}s to register as
     *
     * @param name the registered name
     * @param player the registered player
     */
    public static void register(@NotNull final String name, @NotNull final Player player) {
        try {
            final Object entityPlayer = GET_HANDLE.invoke(player);
            PLAYERS_MAP.put(Version.IS_13_R2_PLUS ? name.toLowerCase(Locale.ENGLISH) : name, entityPlayer);
        } catch (final Exception exception) {
            Bukkit.getLogger().log(Level.SEVERE, "[ModernDisguise] Couldn't put into players map player: " + player.getName(), exception);
        }
    }

    /**
     * Unregisters a name as an online player to allow {@link Player}s to register as
     *
     * @param name the unregistered name
     */
    public static void unregister(@NotNull final String name) {
        PLAYERS_MAP.remove(Version.IS_13_R2_PLUS ? name.toLowerCase(Locale.ENGLISH) : name);
    }

    /**
     * Injects into the {@link Player}'s netty {@link Channel}
     *
     * @param player  the player getting injected into
     * @param handler the {@link ChannelHandler} injected into the channel
     */
    public static void inject(@NotNull final Player player, @NotNull final ChannelHandler handler) {
        final Channel ch = getChannel(player);
        if (ch == null) {
            return;
        }
        ch.eventLoop().submit(() -> {
            if (ch.pipeline().get(HANDLER_NAME) == null) {
                ch.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
            }
        });
    }

    /**
     * Un-injects out of the {@link Player}'s netty channel
     *
     * @param player the player getting un-injected out of
     */
    public static void uninject(@NotNull final Player player) {
        final Channel ch = getChannel(player);
        if (ch == null) {
            return;
        }
        ch.eventLoop().submit(() -> {
            if (ch.pipeline().get(HANDLER_NAME) != null) {
                ch.pipeline().remove(HANDLER_NAME);
            }
        });
    }

    /**
     * @return the {@link Player}'s netty channel
     */
    private static Channel getChannel(@NotNull final Player player) {
        try {
            final Object entityPlayer = GET_HANDLE.invoke(player);
            final Object connection = CONNECTION.get(entityPlayer);
            final Object networkManager = NETWORK_MANAGER.get(connection);
            return NETWORK_CHANNEL.get(networkManager);
        } catch (final Exception exception) {
            Bukkit.getLogger().log(Level.SEVERE, "[ModernDisguise] Couldn't hook into player: " + player.getName(), exception);
            return null;
        }
    }

    /**
     * @return the parsed {@link JSONObject} of the URL input
     */
    public static JSONObject getJSONObject(@NotNull final String urlString) {
        try {
            final Scanner scanner = getScanner(urlString);
            final StringBuilder builder = new StringBuilder();
            while (scanner.hasNext()) {
                builder.append(scanner.next());
            }

            return (JSONObject) new JSONParser().parse(builder.toString());
        } catch (final IOException | ParseException exception) {
            throw new RuntimeException("Failed to Scan/Parse the URL", exception);
        }
    }

    private static @NotNull Scanner getScanner(@NotNull String urlString) throws IOException {
        final URL url = new URL(urlString);
        final HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestProperty("User-Agent", "ModernDisguiseAPI/v1.0");
        connection.setRequestMethod("GET");
        connection.connect();
        if (connection.getResponseCode() != 200) {
            throw new RuntimeException("The used URL doesn't seem to be working (the api is down?) " + urlString);
        }

        return new Scanner(url.openStream());
    }

    /**
     * @return the {@link Skin} of the given {@link Player}
     */
    @SuppressWarnings("all")
    public static @NotNull Skin getSkin(@NotNull final Player player) {
        final GameProfile profile = getProfile(player);
        if (profile == null) {
            return new Skin(null, null);
        }
        final Optional<Property> optional = getProfileProperties(profile).get("textures").stream().findFirst();
        if (optional.isPresent()) {
            return getSkin(optional.get());
        }
        return new Skin(null, null);
    }

    /**
     * @return the {@link Skin} of the given {@link Property}
     */
    @SuppressWarnings("all")
    public static @NotNull Skin getSkin(@NotNull final Property property) {
        final String textures, signature;
        if (Version.IS_20_R2_PLUS) {
            try {
                textures = (String) Property.class.getMethod("value").invoke(property);
                signature = (String) Property.class.getMethod("signature").invoke(property);
            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        } else {
            textures = property.getValue();
            signature = property.getSignature();
        }
        return new Skin(textures, signature);
    }

    // ===== Record Support Methods (authlib 7.0+) =====

    /**
     * Gets the UUID from a GameProfile, handling both authlib 6.x and 7.0+.
     *
     * @param profile the GameProfile
     * @return the UUID
     */
    public static @NotNull UUID getProfileId(@NotNull final GameProfile profile) {
        if (IS_GAME_PROFILE_RECORD && PROFILE_ID_METHOD != null) {
            try {
                return (UUID) PROFILE_ID_METHOD.invoke(profile);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get profile ID via reflection", e);
            }
        } else {
            return profile.getId();
        }
    }

    /**
     * Gets the name from a GameProfile, handling both authlib 6.x and 7.0+.
     *
     * @param profile the GameProfile
     * @return the name
     */
    public static @NotNull String getProfileName(@NotNull final GameProfile profile) {
        if (IS_GAME_PROFILE_RECORD && PROFILE_NAME_ACCESSOR != null) {
            try {
                return (String) PROFILE_NAME_ACCESSOR.invoke(profile);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get profile name via reflection", e);
            }
        } else {
            return profile.getName();
        }
    }

    /**
     * Gets the properties from a GameProfile, handling both authlib 6.x and 7.0+.
     *
     * @param profile the GameProfile
     * @return the PropertyMap
     */
    public static @NotNull Multimap<String, Property> getProfileProperties(@NotNull final GameProfile profile) {
        if (IS_GAME_PROFILE_RECORD && PROFILE_PROPS_METHOD != null) {
            try {
                return (Multimap<String, Property>) PROFILE_PROPS_METHOD.invoke(profile);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get profile properties via reflection", e);
            }
        } else {
            return profile.getProperties();
        }
    }

    /**
     * Creates a new GameProfile with the specified name, keeping the same UUID and properties.
     * Used when GameProfile is a Record (authlib 7.0+) and fields cannot be modified.
     *
     * @param oldProfile the original profile to copy UUID and properties from
     * @param newName    the new name for the profile
     * @return a new GameProfile with the updated name
     */
    public static @NotNull GameProfile createProfileWithName(@NotNull final GameProfile oldProfile, @NotNull final String newName) {
        Multimap<String, Property> properties = LinkedHashMultimap.create();
        properties.putAll(getProfileProperties(oldProfile));
        return createNewProfile(getProfileId(oldProfile), newName, properties);
    }

    /**
     * Creates a new GameProfile with the specified skin, keeping the same UUID and name.
     *
     * @param oldProfile the original profile to copy UUID and name from
     * @param textures   the new skin texture value
     * @param signature  the new skin signature
     * @return a new GameProfile with the updated skin
     */
    public static @NotNull GameProfile createProfileWithSkin(@NotNull final GameProfile oldProfile,
                                                              @NotNull final String textures,
                                                              @Nullable final String signature) {
        Multimap<String, Property> properties = LinkedHashMultimap.create();
        properties.put("textures", new Property("textures", textures, signature));
        return createNewProfile(getProfileId(oldProfile), getProfileName(oldProfile), properties);
    }

    /**
     * Creates a new GameProfile with the specified name and skin.
     *
     * @param oldProfile the original profile to copy UUID from
     * @param newName    the new name for the profile
     * @param textures   the new skin texture value (may be null to keep no skin)
     * @param signature  the new skin signature
     * @return a new GameProfile with the updated name and skin
     */
    public static @NotNull GameProfile createProfileWithNameAndSkin(@NotNull final GameProfile oldProfile,
                                                                     @NotNull final String newName,
                                                                     @Nullable final String textures,
                                                                     @Nullable final String signature) {
        Multimap<String, Property> properties = LinkedHashMultimap.create();
        if (textures != null && !textures.isEmpty()) {
            properties.put("textures", new Property("textures", textures, signature));
        }
        return createNewProfile(getProfileId(oldProfile), newName, properties);
    }

    /**
     * Creates a new GameProfile with the given parameters.
     * Handles both authlib 6.x and 7.0+ (Record) versions.
     *
     * @param uuid       the UUID for the profile
     * @param name       the name for the profile
     * @param properties the properties (skin, etc.)
     * @return a new GameProfile
     */
    public static @NotNull GameProfile createNewProfile(@NotNull final UUID uuid,
                                                         @NotNull final String name,
                                                         @NotNull final Multimap<String, Property> properties) {
        if (IS_GAME_PROFILE_RECORD && PROFILE_CONSTRUCTOR_3ARG != null && PROPERTY_MAP_CONSTRUCTOR != null) {
            try {
                // Create PropertyMap from Multimap
                PropertyMap propertyMap = (PropertyMap) PROPERTY_MAP_CONSTRUCTOR.newInstance(properties);
                // Create GameProfile with 3-arg constructor
                return (GameProfile) PROFILE_CONSTRUCTOR_3ARG.newInstance(uuid, name, propertyMap);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create GameProfile via reflection", e);
            }
        } else {
            // authlib 6.x - create profile and add properties
            GameProfile profile = new GameProfile(uuid, name);
            profile.getProperties().putAll(properties);
            return profile;
        }
    }

    /**
     * Replaces the GameProfile in a player's NMS entity.
     * Used when GameProfile is a Record (authlib 7.0+) and fields cannot be modified.
     *
     * @param player     the Bukkit player
     * @param newProfile the new GameProfile to set
     * @return true if successful, false otherwise
     */
    public static boolean replaceProfile(@NotNull final Player player, @NotNull final GameProfile newProfile) {
        if (PLAYER_PROFILE_FIELD == null) {
            Bukkit.getLogger().warning("[ModernDisguise] PLAYER_PROFILE_FIELD is null - cannot replace profile");
            return false;
        }

        try {
            final Object entityPlayer = GET_HANDLE.invoke(player);
            PLAYER_PROFILE_FIELD.set(entityPlayer, newProfile);
            return true;
        } catch (final Exception exception) {
            Bukkit.getLogger().log(Level.SEVERE, "[ModernDisguise] Failed to replace GameProfile for " + player.getName(), exception);
            return false;
        }
    }

}
