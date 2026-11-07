package justfatlard.knick_knacks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Interaction;

public class Main implements ModInitializer {
	public static final String MOD_ID = "knick-knacks-justfatlard";

	@Override
	public void onInitialize() {
		UseBlockCallback.EVENT.register(SetDown::onUseBlock);
		UseItemCallback.EVENT.register(SetDown::onUseItem);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SetDown.forget(handler.getPlayer()));

		// Hit it to pick it up.
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof Interaction handle) || !Knack.isKnack(handle)) return InteractionResult.PASS;
			if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
			ServerPlayer user = (ServerPlayer) player;
			return Glue.allows(user, handle) && Knack.pickUp(user, server, handle) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
		});

		// Right-click it to use it: eat it, drink it, read it. Sneak to turn it, or to pile on another.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof Interaction handle) || !Knack.isKnack(handle)) return InteractionResult.PASS;
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
			if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
			return rightClick((ServerPlayer) player, server, handle) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
		});

		// A creative player taking a table apart is rebuilding, not knocking things off it.
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel server && player.hasInfiniteMaterials()) Knack.letFloat(server, pos);
		});

		// Isolated in its own class and only reached from here: it names block-tip's types directly,
		// so it must not load without that mod.
		if (FabricLoader.getInstance().isModLoaded("block-tip")) {
			justfatlard.knick_knacks.integration.KnackTips.register();
		}
	}

	private static boolean rightClick(ServerPlayer player, ServerLevel level, Interaction handle) {
		Knack.Pile pile = Knack.pileOf(level, handle);
		if (pile == null) {
			handle.discard();
			return false;
		}
		if (!level.mayInteract(player, handle.blockPosition())) return false;
		if (Glue.isKragle(player.getMainHandItem())) return Glue.use(player, level, pile, player.getMainHandItem());
		if (player.isSecondaryUseActive()) {
			// Something to set down goes on the pile if it matches, beside it if not; clicking a
			// knack is clicking the surface it covers. With nothing to set down, a sneak turns it.
			if (SetDown.isSettingDown(player)) {
				return Glue.mayTouch(player, handle) && Knack.pileOn(player, level, pile)
					|| SetDown.useOrSetDown(player, level, Knack.surfaceHit(pile));
			}
			return Glue.allows(player, handle) && Knack.turn(level, pile);
		}
		// Anybody may read a glued book; only taking it is refused, by the book itself.
		return Reading.open(player, level, pile) || Glue.allows(player, handle) && Bites.take(player, level, pile);
	}
}
