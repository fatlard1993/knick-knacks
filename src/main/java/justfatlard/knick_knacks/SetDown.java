package justfatlard.knick_knacks;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gesture: the thing in the off hand, the main hand empty, sneaking, any face of a block.
 *
 * <p>Vanilla goes first. Whatever the off hand's thing would have done to that block it does: a
 * log is stripped, bone meal grows the grass, a painting hangs, seeds go in the farmland, a
 * candle is placed as a candle. Only a
 * click that vanilla had nothing for sets the thing down, so the gesture never takes a click away
 * from anything. A vanilla client does not know it was taken: it sends the main hand's click,
 * hears nothing back in time, and goes on to send the off hand's click and then the off hand's
 * use, each predicted as vanilla would. Those two arrive a moment behind the first and are refused
 * here, so a bread set down is not also eaten and a log is not stripped twice. The client may
 * start a bite for a moment before the server corrects it.
 *
 * <p>Nothing is refused when nothing was done: a full table, a spot under water. Then the off
 * hand's clicks go through as they always would.
 */
final class SetDown {
	private SetDown() {}

	/** Long enough for the off hand's clicks to arrive behind the main hand's. */
	private static final int SWALLOW_TICKS = 4;

	private static final Map<UUID, Integer> swallowUntil = new ConcurrentHashMap<>();

	static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (player.isSpectator()) return InteractionResult.PASS;
		boolean server = level instanceof ServerLevel;

		if (hand == InteractionHand.OFF_HAND) {
			return server && swallowing(player) ? InteractionResult.FAIL : InteractionResult.PASS;
		}
		if (server) swallowUntil.remove(player.getUUID());
		if (!isSettingDown(player)) return InteractionResult.PASS;

		// A client with this mod knows what is coming and sends no off-hand click at all.
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer user)) return InteractionResult.SUCCESS;

		if (!useOrSetDown(user, serverLevel, hit)) return InteractionResult.PASS;

		swallowUntil.put(user.getUUID(), serverLevel.getServer().getTickCount() + SWALLOW_TICKS);
		return InteractionResult.SUCCESS;
	}

	/**
	 * The off hand's own click on this block, run now through the whole of vanilla's path, and the
	 * thing set down only if that did nothing. The click comes back through {@link #onUseBlock} as
	 * the off hand, which is not swallowing yet and passes. Not for a thing tagged to be set down
	 * whatever vanilla would do: a rocket launches off any block it is used on, and one set down
	 * launches on a pulse instead.
	 */
	static boolean useOrSetDown(ServerPlayer user, ServerLevel level, BlockHitResult hit) {
		boolean vanillaFirst = !user.getOffhandItem().is(Knack.SETS_DOWN);
		if (vanillaFirst && user.gameMode.useItemOn(user, level, user.getOffhandItem(), InteractionHand.OFF_HAND, hit).consumesAction()) return true;
		return Knack.place(user, level, hit);
	}

	static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		return hand == InteractionHand.OFF_HAND && level instanceof ServerLevel && swallowing(player)
			? InteractionResult.FAIL : InteractionResult.PASS;
	}

	static void forget(ServerPlayer player) {
		swallowUntil.remove(player.getUUID());
	}

	/** Sneaking, main hand empty, something in the off hand the gesture may set down. */
	static boolean isSettingDown(Player player) {
		return player.isSecondaryUseActive()
			&& player.getMainHandItem().isEmpty()
			&& !player.getOffhandItem().isEmpty()
			&& Knack.canSetDown(player.getOffhandItem())
			&& player.mayBuild();
	}

	private static boolean swallowing(Player player) {
		Integer until = swallowUntil.get(player.getUUID());
		return until != null && player.level().getServer() != null
			&& player.level().getServer().getTickCount() <= until;
	}
}
