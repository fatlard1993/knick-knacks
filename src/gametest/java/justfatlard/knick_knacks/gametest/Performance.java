package justfatlard.knick_knacks.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * How much a few thousand knacks cost a server, against the same number of vanilla interactions
 * and displays standing in the same places. Only with -Pbench: it is slow, and its result is a
 * number to read, not a pass or a fail.
 *
 * <p>Out of the client's sight and in force-loaded chunks, so what is timed is the overworld's own
 * tick and not the drawing.
 */
public final class Performance implements FabricClientGameTest {
	private static final int SIDE_X = 32;
	private static final int SIDE_Z = 16;
	private static final int TICKS = 400;

	private static volatile boolean timing;
	private static long started;
	private static final long[] samples = new long[TICKS];
	private static volatile int taken;

	static {
		ServerTickEvents.START_LEVEL_TICK.register(level -> {
			if (timing && level.dimension() == Level.OVERWORLD) started = System.nanoTime();
		});
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (!timing || level.dimension() != Level.OVERWORLD || taken >= TICKS) return;
			samples[taken++] = System.nanoTime() - started;
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!Boolean.getBoolean("knick-knacks.bench")) return;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			BlockPos floor = new BlockPos(600, 100, 0);
			int x2 = floor.getX() + SIDE_X - 1, z2 = floor.getZ() + SIDE_Z - 1;
			server.runCommand("forceload add " + floor.getX() + " " + floor.getZ() + " " + x2 + " " + z2);
			server.runCommand("fill " + floor.getX() + " " + floor.getY() + " " + floor.getZ() + " " + x2 + " " + floor.getY() + " " + z2 + " stone");
			server.runCommand("gamerule spawn_mobs false");
			context.waitTicks(40);

			report("nothing", measure(context));

			spawnVanilla(server, floor);
			report("vanilla interaction + display", measure(context));
			clear(server, context);

			place(server, connection, floor, () -> new ItemStack(Items.BREAD, 64));
			report("knacks: bread", measure(context));
			clear(server, context);

			place(server, connection, floor, () -> new ItemStack(Items.FIREWORK_ROCKET, 64));
			report("knacks: rockets (fuse)", measure(context));
			clear(server, context);

			place(server, connection, floor, () -> {
				ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
				book.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
				return book;
			});
			report("knacks: enchanted books (motes)", measure(context));
			clear(server, context);
		}
	}

	private static int count() {
		return SIDE_X * SIDE_Z * 4;
	}

	private static void spawnVanilla(TestServerContext server, BlockPos floor) {
		server.runOnServer(s -> {
			ServerLevel level = s.overworld();
			forEachSpot(floor, at -> {
				Interaction handle = new Interaction(EntityTypes.INTERACTION, level);
				handle.setPos(at);
				handle.setWidth(0.5f);
				handle.setHeight(0.125f);
				level.addFreshEntity(handle);
				Display.ItemDisplay shown = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
				shown.setPos(at);
				shown.setItemStack(new ItemStack(Items.BREAD));
				shown.setItemTransform(ItemDisplayContext.FIXED);
				level.addFreshEntity(shown);
			});
		});
	}

	private static void place(TestServerContext server, TestServerConnection connection, BlockPos floor, Supplier<ItemStack> stack) {
		int placed = server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			ServerPlayer player = connection.getServerPlayer();
			int[] n = {0};
			forEachSpot(floor, at -> {
				player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				player.setItemInHand(InteractionHand.OFF_HAND, stack.get());
				player.setShiftKeyDown(true);
				BlockPos block = BlockPos.containing(at.x, at.y - 0.5, at.z);
				if (player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
						new BlockHitResult(at, Direction.UP, block, false)).consumesAction()) n[0]++;
				player.setShiftKeyDown(false);
			});
			player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
			return n[0];
		});
		if (placed != count()) throw new AssertionError("only " + placed + " of " + count() + " knacks went down");
	}

	private static void forEachSpot(BlockPos floor, java.util.function.Consumer<Vec3> each) {
		for (int x = 0; x < SIDE_X; x++) for (int z = 0; z < SIDE_Z; z++) {
			for (double dx : new double[] {0.25, 0.75}) for (double dz : new double[] {0.25, 0.75}) {
				each.accept(new Vec3(floor.getX() + x + dx, floor.getY() + 1, floor.getZ() + z + dz));
			}
		}
	}

	private static void clear(TestServerContext server, ClientGameTestContext context) {
		server.runCommand("kill @e[type=minecraft:interaction]");
		server.runCommand("kill @e[type=minecraft:item_display]");
		server.runCommand("kill @e[type=minecraft:item]");
		server.runCommand("kill @e[type=minecraft:firework_rocket]");
		context.waitTicks(20);
	}

	private static long[] measure(ClientGameTestContext context) {
		context.waitTicks(40);
		taken = 0;
		timing = true;
		context.waitTicks(TICKS + 5);
		timing = false;
		return Arrays.copyOf(samples, taken);
	}

	private static void report(String what, long[] nanos) {
		long[] sorted = nanos.clone();
		Arrays.sort(sorted);
		double mean = Arrays.stream(sorted).average().orElse(0) / 1.0E6;
		double median = sorted.length == 0 ? 0 : sorted[sorted.length / 2] / 1.0E6;
		double p99 = sorted.length == 0 ? 0 : sorted[(int) (sorted.length * 0.99)] / 1.0E6;
		System.out.printf("[bench] %-40s %5d entities-pairs  mean %.3f ms  median %.3f ms  p99 %.3f ms  (%d ticks)%n",
			what, count(), mean, median, p99, sorted.length);
	}
}
