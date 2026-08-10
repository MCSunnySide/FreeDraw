package com.github.squi2rel.freedraw;

import com.github.squi2rel.freedraw.network.DrawPayload;
import com.github.squi2rel.freedraw.network.ServerPacketHandler;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.DyeColor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@SuppressWarnings("resource")
public class FreeDraw implements ModInitializer {
	public static final String MOD_ID = "freedraw";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final String version = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getMetadata().getVersion().toString();
	public static final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("freedraw-server.json");

	public static MinecraftServer server;

	private static final SimpleCommandExceptionType PARSE_EXCEPTION = new SimpleCommandExceptionType(Text.literal("Invalid color"));

	@Override
	public void onInitialize() {
		DrawPayload.register();

		CommandRegistrationCallback.EVENT.register((d, c, e) -> {
			if (e.integrated) {
				d.register(CommandManager.literal("drawcolor")
						.requires(ServerCommandSource::isExecutedByPlayer)
						.then(CommandManager.argument("color", StringArgumentType.string())
								.executes(s -> {
									String str = s.getArgument("color", String.class).toLowerCase();
									if (str.equalsIgnoreCase("rainbow")) {
										ServerPlayerEntity player = s.getSource().getPlayerOrThrow();
										ServerPacketHandler.sendTo(player, ServerPacketHandler.color(0));
										player.sendMessage(createRainbowText("Current color: RAINBOW", 0.8f, 1.0f));
										return 1;
									}
									DyeColor dyeColor = DyeColor.byId(str, null);
									int color;
									if (dyeColor != null) {
										color = dyeColor.getSignColor() | 0xFF000000;
									} else {
										color = parseColor(str);
									}
									ServerPlayerEntity player = s.getSource().getPlayerOrThrow();
									ServerPacketHandler.sendTo(player, ServerPacketHandler.color(color));
									player.sendMessage(Text.literal("Current color: " + String.format("#%06X", color & 0xFFFFFF)).setStyle(Style.EMPTY.withColor(color)));
									return 1;
								})
						)
				);
			}
		});

		ServerLifecycleEvents.SERVER_STARTING.register(s -> {
			FreeDraw.server = s;
			DataHolder.load();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> DataHolder.save());
		ServerLifecycleEvents.BEFORE_SAVE.register((s, flush, force) -> DataHolder.save());
		ServerTickEvents.END_SERVER_TICK.register(s -> DataHolder.update());
		ServerPlayNetworking.registerGlobalReceiver(DrawPayload.ID, (p, c) -> c.server().execute(() -> {
            ByteBuf buf = Unpooled.wrappedBuffer(p.data());
            try {
                ServerPacketHandler.handle(c.player(), buf);
            } catch (Exception e) {
                c.player().networkHandler.disconnect(Text.of(e.toString()));
            } finally {
                buf.release();
            }
        }));
		ServerPlayConnectionEvents.JOIN.register((h, p, s) -> DataHolder.onPlayerJoin(h.player));
		ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> DataHolder.onPlayerLeave(h.player));
	}

	public static MutableText createRainbowText(String content, float saturation, float brightness) {
		MutableText result = Text.empty();
		int length = content.length();

		for (int i = 0; i < length; i++) {
			float hue = (float) i / length;
			int colorRGB = hsbToRgb(hue, saturation, brightness);

			TextColor color = TextColor.fromRgb(colorRGB & 0xFFFFFF);
			result.append(Text.literal(String.valueOf(content.charAt(i))).styled(style -> style.withColor(color)));
		}

		return result;
	}

	public static int hsbToRgb(float h, float s, float b) {
		int r = 0, g = 0, b_ = 0;
		if (s == 0) {
			r = g = b_ = (int) (b * 255.0f + 0.5f);
		} else {
			float h_ = (h - (float) Math.floor(h)) * 6.0f;
			float f = h_ - (float) Math.floor(h_);
			float p = b * (1.0f - s);
			float q = b * (1.0f - s * f);
			float t = b * (1.0f - (s * (1.0f - f)));
			switch ((int) h_) {
				case 0 -> { r = (int) (b * 255.0f + 0.5f); g = (int) (t * 255.0f + 0.5f); b_ = (int) (p * 255.0f + 0.5f); }
				case 1 -> { r = (int) (q * 255.0f + 0.5f); g = (int) (b * 255.0f + 0.5f); b_ = (int) (p * 255.0f + 0.5f); }
				case 2 -> { r = (int) (p * 255.0f + 0.5f); g = (int) (b * 255.0f + 0.5f); b_ = (int) (t * 255.0f + 0.5f); }
				case 3 -> { r = (int) (p * 255.0f + 0.5f); g = (int) (q * 255.0f + 0.5f); b_ = (int) (b * 255.0f + 0.5f); }
				case 4 -> { r = (int) (t * 255.0f + 0.5f); g = (int) (p * 255.0f + 0.5f); b_ = (int) (b * 255.0f + 0.5f); }
				case 5 -> { r = (int) (b * 255.0f + 0.5f); g = (int) (p * 255.0f + 0.5f); b_ = (int) (q * 255.0f + 0.5f); }
			}
		}
		return 0xFF000000 | (r << 16) | (g << 8) | b_;
	}

	public static boolean checkVersion(String v) {
		String[] p1 = StringUtils.split(v, '.');
		String[] p2 = StringUtils.split(version, '.');
		if (p1.length < 2 || p2.length < 2) return false;
		return p1[0].equals(p2[0]) && p1[1].equals(p2[1]);
	}

	public static int parseColor(String colorStr) throws CommandSyntaxException {
		String hex = colorStr.startsWith("#") ? colorStr.substring(1) : colorStr;
		if (hex.length() != 6 && hex.length() != 8) throw PARSE_EXCEPTION.create();
		int r = Integer.parseInt(hex.substring(0, 2), 16);
		int g = Integer.parseInt(hex.substring(2, 4), 16);
		int b = Integer.parseInt(hex.substring(4, 6), 16);
		int a = 0xFF;
		if (hex.length() == 8) {
			a = Integer.parseInt(hex.substring(6, 8), 16);
		}
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}