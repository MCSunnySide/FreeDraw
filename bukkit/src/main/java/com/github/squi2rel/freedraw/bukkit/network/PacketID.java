package com.github.squi2rel.freedraw.bukkit.network;

/**
 * Wire protocol packet ids. Must match
 * {@code com.github.squi2rel.freedraw.network.PacketID} from the Fabric mod.
 */
public class PacketID {
    public static final int
            CONFIG = 0,
            NEW_PATH = 1,
            CREATE_PATH = 2,
            REMOVE_PATH = 3,
            ADD_POINTS = 4,
            MAX_POINTS = 5,
            COLOR = 6;
}
