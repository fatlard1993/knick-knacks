package justfatlard.knick_knacks.gametest;

import justfatlard.pandorical.gametest.Pictures;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Set down, nudged, refused when full, piled, turned, eaten, read, picked up, and dropped when the
 * table goes: all through the server's own click and packet paths, the way a vanilla client's
 * clicks arrive.
 *
 * <p>The cookie is drawn being eaten in two stages for this run only, by two models in the test's
 * resources at the path Let's Cook draws its own, so the bite-by-bite path runs without it.
 */
public final class SetDownAndPickUp implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");
			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());

			fourToABlock(context, server, connection, origin.east(2));
			leftToVanilla(server, connection, origin.east(2).south(2));
			standAt(server, connection, origin.west(3).south(2));
			walls(context, server, connection, origin.west(6));
			standAt(server, connection, origin.west(3).north(5));
			ceilings(context, server, connection, origin.west(4).north(6).above(2));
			standAt(server, connection, origin.west(6).north(1));
			glowAndFloat(context, server, connection, origin.west(6).north(3));
			standAt(server, connection, origin.west(6).south(5));
			setDressing(context, server, connection, origin.west(6).south(3));
			standAt(server, connection, origin);
			piles(context, server, connection, origin.east(2).north(2));
			eating(server, connection, origin.west(2));
			reading(server, connection, origin.west(2).north(2));
			restsOnASlab(server, connection, origin.west(2).south(2));
			picture(context, server, connection, origin.east(6));
			larder(context, server, connection, origin.east(6).south(12));
		}
	}

	private static void fourToABlock(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos table) {
		block(server, table, Blocks.OAK_PLANKS);

		hold(server, connection, new ItemStack(Items.BREAD, 5));
		check(setDown(server, connection, table, 0.2, 0.2).consumesAction(), "the first bread was not set down");
		check(setDown(server, connection, table, 0.8, 0.2).consumesAction(), "the second bread was not set down");
		check(setDown(server, connection, table, 0.5, 0.3).consumesAction(), "a click between two was not nudged to a free corner");
		check(setDown(server, connection, table, 0.8, 0.8).consumesAction(), "the fourth bread was not set down");
		InteractionResult fifth = setDown(server, connection, table, 0.5, 0.5);
		check(!fifth.consumesAction(), "a fifth fitted on one block: " + fifth);
		check(offHandCount(server, connection) == 1, "the off hand should hold the one left: " + offHandCount(server, connection));
		String corners = server.computeOnServer(s -> corners(s.overworld(), table));
		check(corners.equals("0.25,0.25 0.25,0.75 0.75,0.25 0.75,0.75"), "not one to a corner: " + corners);
		double top = server.computeOnServer(s -> handlesIn(s.overworld(), table).get(0).getY());
		check(Math.abs(top - (table.getY() + 1)) < 1.0E-6, "not resting on the top face: " + top);

		// Take the table away and what was on it falls as items.
		block(server, table, Blocks.AIR);
		context.waitTicks(25);
		check(handles(server, table).isEmpty(), "bread is still floating where the table was");
		int dropped = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class,
			new AABB(table).inflate(2), item -> item.getItem().is(Items.BREAD)).stream().mapToInt(item -> item.getItem().getCount()).sum());
		check(dropped == 4, "the table's bread did not fall as four items: " + dropped);
	}

	private static void leftToVanilla(TestServerContext server, TestServerConnection connection, BlockPos spot) {
		block(server, spot, Blocks.GRASS_BLOCK);

		// A thing with a placed form of its own is placed as itself.
		hold(server, connection, new ItemStack(Items.CANDLE, 3));
		setDown(server, connection, spot, 0.5, 0.5);
		check(handles(server, spot).isEmpty(), "a candle was set down as a knack");
		check(server.computeOnServer(s -> s.overworld().getBlockState(spot.above()).is(Blocks.CANDLE)), "a candle was not placed as a candle");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(spot.above(), Blocks.AIR.defaultBlockState()));

		// A poppy is planted in grass, and stands on a table, where it cannot grow.
		hold(server, connection, new ItemStack(Items.POPPY, 2));
		setDown(server, connection, spot, 0.5, 0.5);
		check(handles(server, spot).isEmpty(), "a poppy on grass was set down rather than planted");
		check(server.computeOnServer(s -> s.overworld().getBlockState(spot.above()).is(Blocks.POPPY)), "a poppy on grass was not planted");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(spot.above(), Blocks.AIR.defaultBlockState()));
		BlockPos vase = spot.west(2);
		block(server, vase, Blocks.OAK_PLANKS);
		check(setDown(server, connection, vase, 0.5, 0.5).consumesAction(), "a poppy was not set down on a table");
		check(server.computeOnServer(s -> handlesIn(s.overworld(), vase).get(0).getBbHeight()) == 0.5F, "the poppy on the table is not standing");

		// Vanilla goes first: bone meal on grass grows it, and is not set down.
		hold(server, connection, new ItemStack(Items.BONE_MEAL, 3));
		check(setDown(server, connection, spot, 0.5, 0.5).consumesAction(), "bone meal on grass did nothing");
		check(handles(server, spot).isEmpty(), "bone meal on grass was set down rather than spent");
		check(offHandCount(server, connection) == 2, "bone meal on grass was not spent: " + offHandCount(server, connection));

		// Bone meal on a table has nothing to grow, so it goes down; and the off hand's own use,
		// arriving behind the main hand's click, is not also acted on: bread set down is not eaten.
		BlockPos table = spot.east(2);
		block(server, table, Blocks.OAK_PLANKS);
		check(setDown(server, connection, table, 0.2, 0.2).consumesAction(), "bone meal on a table was not set down");
		server.runOnServer(s -> connection.getServerPlayer().getFoodData().setFoodLevel(6));
		hold(server, connection, new ItemStack(Items.BREAD, 2));
		check(setDown(server, connection, table, 0.8, 0.8).consumesAction(), "the bread was not set down");
		boolean eating = server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.gameMode.useItem(player, s.overworld(), player.getOffhandItem(), InteractionHand.OFF_HAND);
			return player.isUsingItem();
		});
		check(!eating, "the swallowed use still started eating the bread");

		// A carrot on stone has nowhere to grow, so it is set down; on farmland it is planted.
		BlockPos field = spot.east(4);
		block(server, field, Blocks.STONE);
		hold(server, connection, new ItemStack(Items.CARROT, 2));
		check(setDown(server, connection, field, 0.5, 0.5).consumesAction(), "a carrot was not set down on stone");
		BlockPos farmland = field.south(2);
		block(server, farmland, Blocks.FARMLAND);
		setDown(server, connection, farmland, 0.5, 0.5);
		check(handles(server, farmland).isEmpty(), "a carrot on farmland was set down rather than planted");
		boolean planted = server.computeOnServer(s -> s.overworld().getBlockState(farmland.above()).is(Blocks.CARROTS));
		check(planted, "a carrot on farmland was not planted");

		// A bucket is never set down: its use is aimed past the block, at water or a place to pour.
		BlockPos tub = spot.south(2);
		block(server, tub, Blocks.OAK_PLANKS);
		hold(server, connection, new ItemStack(Items.WATER_BUCKET));
		check(!setDown(server, connection, tub, 0.5, 0.5).consumesAction(), "a water bucket was taken by the gesture");
		check(handles(server, tub).isEmpty(), "a water bucket was set down");

		// An axe strips a log's side; on planks it has nothing to do, so it hangs there.
		BlockPos log = spot.south(4);
		block(server, log, Blocks.OAK_LOG);
		block(server, log.east(), Blocks.AIR);
		hold(server, connection, new ItemStack(Items.IRON_AXE));
		onFace(server, connection, log, Direction.EAST, 0.5, 0.5);
		check(server.computeOnServer(s -> s.overworld().getBlockState(log).is(Blocks.STRIPPED_OAK_LOG)), "the axe did not strip the log");
		check(handlesNear(server, log).isEmpty(), "the axe hung on the log rather than stripping it");
		block(server, log, Blocks.OAK_PLANKS);
		check(onFace(server, connection, log, Direction.EAST, 0.5, 0.5).consumesAction(), "the axe did not hang on the planks");

		// A painting hangs as a painting.
		BlockPos gallery = spot.south(6);
		block(server, gallery, Blocks.STONE);
		block(server, gallery.east(), Blocks.AIR);
		hold(server, connection, new ItemStack(Items.PAINTING));
		onFace(server, connection, gallery, Direction.EAST, 0.5, 0.5);
		check(handlesNear(server, gallery).isEmpty(), "a painting was set down rather than hung");

		// Nothing goes down under water, so a bottle aimed at the bottom of a pool can still fill.
		BlockPos pool = spot.south(8);
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(pool, Blocks.STONE.defaultBlockState());
			s.overworld().setBlockAndUpdate(pool.above(), Blocks.WATER.defaultBlockState());
		});
		hold(server, connection, new ItemStack(Items.BREAD));
		check(!setDown(server, connection, pool, 0.5, 0.5).consumesAction(), "bread was set down under water");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(pool.above(), Blocks.AIR.defaultBlockState()));
	}

	private static void walls(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos wall) {
		block(server, wall, Blocks.STONE);
		block(server, wall.east(), Blocks.AIR);

		hold(server, connection, new ItemStack(Items.MAP, 5));
		check(onFace(server, connection, wall, Direction.EAST, 0.2, 0.8).consumesAction(), "the first map did not hang");
		check(onFace(server, connection, wall, Direction.EAST, 0.8, 0.8).consumesAction(), "the second map did not hang");
		check(onFace(server, connection, wall, Direction.EAST, 0.2, 0.2).consumesAction(), "the third map did not hang");
		check(onFace(server, connection, wall, Direction.EAST, 0.8, 0.2).consumesAction(), "the fourth map did not hang");
		check(!onFace(server, connection, wall, Direction.EAST, 0.5, 0.5).consumesAction(), "a fifth fitted on one wall");
		List<Interaction> hung = handlesNear(server, wall);
		check(hung.size() == 4, "four maps did not hang: " + hung.size());
		double out = hung.get(0).getX() - (wall.getX() + 1);
		check(out > 0 && out < 0.05, "a map is not just off the wall: " + out);
		float tall = server.computeOnServer(s -> hung.get(0).getBbHeight());
		check(tall == 0.5F, "a hanging knack's hitbox is not square: " + tall);

		// Turned about its own facing, like a framed map.
		hold(server, connection, ItemStack.EMPTY);
		String before = server.computeOnServer(s -> turnOfNear(s.overworld(), hung.get(0)));
		rightClick(server, connection, hung.get(0), true);
		String after = server.computeOnServer(s -> turnOfNear(s.overworld(), hung.get(0)));
		check(!before.equals(after), "a sneak did not turn a hanging map: " + before + " -> " + after);

		// The wall goes, the maps fall.
		block(server, wall, Blocks.AIR);
		context.waitTicks(25);
		check(handlesNear(server, wall).isEmpty(), "maps are still hanging where the wall was");
		int dropped = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class,
			new AABB(wall).inflate(2), item -> item.getItem().is(Items.MAP)).stream().mapToInt(item -> item.getItem().getCount()).sum());
		check(dropped == 4, "the wall's maps did not fall as four items: " + dropped);
	}

	private static void ceilings(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos ceiling) {
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(ceiling, Blocks.OAK_PLANKS.defaultBlockState());
			s.overworld().setBlockAndUpdate(ceiling.below(), Blocks.AIR.defaultBlockState());
			s.overworld().setBlockAndUpdate(ceiling.below(2), Blocks.AIR.defaultBlockState());
		});

		// A ham hangs from the underside of a block, its hitbox the half block below.
		hold(server, connection, new ItemStack(Items.PORKCHOP, 5));
		check(onFace(server, connection, ceiling, Direction.DOWN, 0.2, 0.2).consumesAction(), "the ham did not hang");
		List<Interaction> hung = handlesNear(server, ceiling);
		check(hung.size() == 1, "one ham did not hang: " + hung.size());
		double below = ceiling.getY() - hung.get(0).getY();
		check(Math.abs(below - 0.5) < 1.0E-6, "the ham does not hang half a block below the ceiling: " + below);
		for (double[] at : new double[][] {{0.8, 0.2}, {0.2, 0.8}, {0.8, 0.8}}) onFace(server, connection, ceiling, Direction.DOWN, at[0], at[1]);
		check(handlesNear(server, ceiling).size() == 4, "four hams did not hang from one block");
		check(!onFace(server, connection, ceiling, Direction.DOWN, 0.5, 0.5).consumesAction(), "a fifth ham hung from one block");

		// The ceiling goes, the hams fall.
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(ceiling, Blocks.AIR.defaultBlockState()));
		context.waitTicks(25);
		check(handlesNear(server, ceiling).isEmpty(), "hams are still hanging where the ceiling was");
	}

	private static void glowAndFloat(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos table) {
		block(server, table, Blocks.OAK_PLANKS);

		// Glowstone dust lights the air it sits in, and takes the light with it.
		hold(server, connection, new ItemStack(Items.GLOWSTONE_DUST));
		setDown(server, connection, table, 0.5, 0.5);
		check(server.computeOnServer(s -> s.overworld().getBlockState(table.above()).is(Blocks.LIGHT)), "the glowstone dust gave no light");
		hit(server, connection, handles(server, table).get(0), false);
		check(server.computeOnServer(s -> s.overworld().getBlockState(table.above()).isAir()), "the light stayed after the dust was picked up");

		// A creative player taking the table apart leaves what was on it where it was.
		hold(server, connection, new ItemStack(Items.BREAD));
		setDown(server, connection, table, 0.5, 0.5);
		server.runCommand("gamemode creative @a");
		server.runOnServer(s -> connection.getServerPlayer().gameMode.destroyBlock(table));
		context.waitTicks(25);
		check(handles(server, table).size() == 1, "the bread fell when a creative player broke the table");

		// Rebuilt, it is an ordinary knack again, and falls the next time.
		server.runCommand("gamemode survival @a");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(table, Blocks.OAK_PLANKS.defaultBlockState()));
		context.waitTicks(25);
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(table, Blocks.AIR.defaultBlockState()));
		context.waitTicks(25);
		check(handles(server, table).isEmpty(), "the bread floated on after the table was rebuilt and broken again");
	}

	private static void setDressing(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos table) {
		block(server, table, Blocks.OAK_PLANKS);
		block(server, table.east(), Blocks.OAK_PLANKS);

		// A snowball through a lingering potion breaks it, and leaves the cloud.
		hold(server, connection, PotionContents.createItemStack(Items.LINGERING_POTION, Potions.REGENERATION));
		setDown(server, connection, table, 0.5, 0.5);
		Interaction bottle = handles(server, table).get(0);
		server.runOnServer(s -> {
			Snowball snowball = new Snowball(s.overworld(), bottle.getX(), bottle.getY() + 2, bottle.getZ(), new ItemStack(Items.SNOWBALL));
			snowball.setDeltaMovement(0, -1.5, 0);
			s.overworld().addFreshEntity(snowball);
		});
		context.waitTicks(10);
		check(bottle.isRemoved(), "the snowball did not break the bottle");
		int clouds = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(AreaEffectCloud.class, new AABB(table).inflate(3)).size());
		check(clouds == 1, "the broken lingering potion left no cloud: " + clouds);

		// A bottle glued down is not broken: the snowball goes straight through.
		hold(server, connection, PotionContents.createItemStack(Items.POTION, Potions.WATER));
		setDown(server, connection, table, 0.5, 0.5);
		Interaction glued = handles(server, table).get(0);
		glue(server, glued);
		server.runOnServer(s -> {
			Snowball snowball = new Snowball(s.overworld(), glued.getX(), glued.getY() + 2, glued.getZ(), new ItemStack(Items.SNOWBALL));
			snowball.setDeltaMovement(0, -1.5, 0);
			s.overworld().addFreshEntity(snowball);
		});
		context.waitTicks(10);
		check(!glued.isRemoved(), "a glued bottle broke");

		// Glued, it stays when the table goes.
		block(server, table, Blocks.AIR);
		context.waitTicks(25);
		check(!glued.isRemoved(), "a glued bottle fell when the table went");

		// A rocket goes up on a pulse: one a pulse, from the top of the pile.
		BlockPos pad = table.east();
		hold(server, connection, new ItemStack(Items.FIREWORK_ROCKET, 2));
		setDown(server, connection, pad, 0.5, 0.5);
		Interaction rockets = handles(server, pad).get(0);
		rightClick(server, connection, rockets, true);
		check(pileSize(server, rockets) == 2, "the second rocket did not pile on: " + pileSize(server, rockets));
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(pad.north(), Blocks.REDSTONE_BLOCK.defaultBlockState()));
		context.waitTicks(3);
		check(pileSize(server, rockets) == 1, "the pulse did not launch one rocket: " + pileSize(server, rockets));
		int flying = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(FireworkRocketEntity.class, new AABB(pad).inflate(8)).size());
		check(flying == 1, "no rocket went up: " + flying);
		context.waitTicks(5);
		check(pileSize(server, rockets) == 1, "a steady signal launched another");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(pad.north(), Blocks.AIR.defaultBlockState()));
		context.waitTicks(3);
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(pad.north(), Blocks.REDSTONE_BLOCK.defaultBlockState()));
		context.waitTicks(3);
		check(rockets.isRemoved(), "the second pulse did not launch the last rocket");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(pad.north(), Blocks.AIR.defaultBlockState()));
	}

	private static void piles(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos table) {
		block(server, table, Blocks.OAK_PLANKS);

		hold(server, connection, new ItemStack(Items.GOLD_INGOT, 12));
		check(setDown(server, connection, table, 0.2, 0.2).consumesAction(), "the first ingot was not set down");
		Interaction pile = server.computeOnServer(s -> handlesIn(s.overworld(), table).get(0));
		for (int i = 0; i < 7; i++) rightClick(server, connection, pile, true);
		check(pileSize(server, pile) == 8, "seven more did not make a pile of eight: " + pileSize(server, pile));
		float height = server.computeOnServer(s -> pile.getBbHeight());
		check(height > 0.3F, "the interaction did not grow with the pile: " + height);

		// A full pile takes no more: the next one goes down beside it.
		rightClick(server, connection, pile, true);
		check(pileSize(server, pile) == 8, "a pile went past eight: " + pileSize(server, pile));
		check(handles(server, table).size() == 2, "the ninth ingot did not go down beside the full pile");
		check(offHandCount(server, connection) == 3, "the pile spent the wrong number of ingots: " + offHandCount(server, connection));

		// Something else, sneak-clicked on the pile, goes down beside it rather than on it.
		hold(server, connection, new ItemStack(Items.EMERALD, 1));
		rightClick(server, connection, pile, true);
		check(handles(server, table).size() == 3, "the emerald did not go down beside the pile");
		check(pileSize(server, pile) == 8, "the emerald went on the pile");

		// With nothing to set down, a sneak turns it.
		hold(server, connection, ItemStack.EMPTY);
		String before = server.computeOnServer(s -> turnOf(s.overworld(), pile));
		rightClick(server, connection, pile, true);
		String after = server.computeOnServer(s -> turnOf(s.overworld(), pile));
		check(!before.equals(after), "a sneaking right-click did not turn it: " + before + " -> " + after);

		// Hit takes the top one; a sneaking hit takes the rest.
		hit(server, connection, pile, false);
		check(pileSize(server, pile) == 7, "a hit did not take just the top: " + pileSize(server, pile));
		hit(server, connection, pile, true);
		context.waitTick();
		check(pile.isRemoved(), "a sneaking hit did not take the whole pile");
		int ingots = server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.GOLD_INGOT));
		check(ingots == 8, "the pile did not come back as eight ingots: " + ingots);
	}

	private static void eating(TestServerContext server, TestServerConnection connection, BlockPos table) {
		block(server, table, Blocks.OAK_PLANKS);
		server.runOnServer(s -> connection.getServerPlayer().getFoodData().setFoodLevel(6));

		// Bread has no pictures of being eaten, so it goes in one bite.
		hold(server, connection, new ItemStack(Items.BREAD, 1));
		setDown(server, connection, table, 0.2, 0.2);
		Interaction bread = server.computeOnServer(s -> handlesIn(s.overworld(), table).get(0));
		check(rightClick(server, connection, bread, false).consumesAction(), "the bread was not eaten");
		check(handles(server, table).isEmpty(), "the eaten bread is still on the table");
		int food = server.computeOnServer(s -> connection.getServerPlayer().getFoodData().getFoodLevel());
		check(food == 11, "eating the bread did not feed the player: " + food);

		// The cookie has two, so it shows them a bite at a time and goes on the third.
		hold(server, connection, new ItemStack(Items.COOKIE, 1));
		setDown(server, connection, table, 0.8, 0.2);
		Interaction cookie = server.computeOnServer(s -> handlesIn(s.overworld(), table).get(0));
		rightClick(server, connection, cookie, false);
		check(server.computeOnServer(s -> bitesShown(s.overworld(), table)).equals("bites_1"), "the first bite was not drawn");
		rightClick(server, connection, cookie, false);
		check(server.computeOnServer(s -> bitesShown(s.overworld(), table)).equals("bites_2"), "the second bite was not drawn");
		rightClick(server, connection, cookie, false);
		check(handles(server, table).isEmpty(), "the cookie was not finished on its third bite");

		// A stew leaves its bowl where it stood.
		hold(server, connection, new ItemStack(Items.MUSHROOM_STEW, 1));
		setDown(server, connection, table, 0.2, 0.8);
		Interaction stew = server.computeOnServer(s -> handlesIn(s.overworld(), table).get(0));
		rightClick(server, connection, stew, false);
		String left = server.computeOnServer(s -> showing(s.overworld(), table));
		check(left.equals("minecraft:bowl"), "the stew did not leave its bowl on the table: " + left);

		// A potion stands, and leaves its bottle standing.
		ItemStack potion = PotionContents.createItemStack(Items.POTION, Potions.WATER);
		hold(server, connection, potion);
		setDown(server, connection, table, 0.8, 0.8);
		Interaction bottle = server.computeOnServer(s -> handlesIn(s.overworld(), table).stream()
			.filter(h -> Math.abs(h.getX() - table.getX() - 0.75) < 0.01 && Math.abs(h.getZ() - table.getZ() - 0.75) < 0.01).findFirst().orElseThrow());
		check(server.computeOnServer(s -> bottle.getBbHeight()) == 0.5F, "the potion is not standing");
		rightClick(server, connection, bottle, false);
		check(server.computeOnServer(s -> bottle.getBbHeight()) == 0.5F, "the empty bottle is not standing");

		// Not hungry, nothing is eaten.
		server.runOnServer(s -> connection.getServerPlayer().getFoodData().setFoodLevel(20));
		BlockPos full = table.east(2);
		block(server, full, Blocks.OAK_PLANKS);
		hold(server, connection, new ItemStack(Items.BREAD, 1));
		setDown(server, connection, full, 0.5, 0.5);
		Interaction uneaten = server.computeOnServer(s -> handlesIn(s.overworld(), full).get(0));
		rightClick(server, connection, uneaten, false);
		check(!handles(server, full).isEmpty(), "bread was eaten by a full player");
	}

	private static void reading(TestServerContext server, TestServerConnection connection, BlockPos desk) {
		block(server, desk, Blocks.OAK_PLANKS);
		ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
		book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(
			net.minecraft.server.network.Filterable.passThrough("Dear diary"))));
		hold(server, connection, book);
		check(setDown(server, connection, desk, 0.5, 0.5).consumesAction(), "the book was not set down");
		Interaction handle = server.computeOnServer(s -> handlesIn(s.overworld(), desk).get(0));
		rightClick(server, connection, handle, false);
		boolean open = server.computeOnServer(s -> connection.getServerPlayer().containerMenu instanceof LecternMenu);
		check(open, "right-clicking the book did not open it");

		// Take Book picks it up.
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.containerMenu.clickMenuButton(player, LecternMenu.BUTTON_TAKE_BOOK);
			player.closeContainer();
		});
		check(handles(server, desk).isEmpty(), "Take Book left the book on the desk");
		int books = server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.WRITABLE_BOOK));
		check(books == 1, "Take Book did not give the book back: " + books);
	}

	private static void restsOnASlab(TestServerContext server, TestServerConnection connection, BlockPos slab) {
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(slab, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
			s.overworld().setBlockAndUpdate(slab.above(), Blocks.AIR.defaultBlockState());
		});
		hold(server, connection, new ItemStack(Items.APPLE, 1));
		BlockHitResult onSlab = new BlockHitResult(Vec3.atBottomCenterOf(slab).add(0, 0.5, 0), Direction.UP, slab, false);
		check(click(server, connection, onSlab).consumesAction(), "the apple was not set down on the slab");
		double slabTop = server.computeOnServer(s -> handlesIn(s.overworld(), slab).get(0).getY());
		check(Math.abs(slabTop - (slab.getY() + 0.5)) < 1.0E-6, "not resting on the slab: " + slabTop);
	}

	/** A table set for a picture, to look at by eye. */
	private static void picture(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos table) {
		block(server, table, Blocks.SPRUCE_PLANKS);
		block(server, table.east(), Blocks.SPRUCE_PLANKS);
		hold(server, connection, new ItemStack(Items.BREAD));
		setDown(server, connection, table, 0.2, 0.2);
		hold(server, connection, PotionContents.createItemStack(Items.POTION, Potions.HEALING));
		setDown(server, connection, table, 0.8, 0.2);
		hold(server, connection, new ItemStack(Items.BOOK, 5));
		setDown(server, connection, table, 0.2, 0.8);
		Interaction books = server.computeOnServer(s -> handlesIn(s.overworld(), table).stream()
			.filter(h -> h.getZ() - table.getZ() > 0.5 && h.getX() - table.getX() < 0.5).findFirst().orElseThrow());
		for (int i = 0; i < 4; i++) rightClick(server, connection, books, true);
		hold(server, connection, new ItemStack(Items.COOKIE, 2));
		setDown(server, connection, table, 0.8, 0.8);
		hold(server, connection, new ItemStack(Items.GOLD_INGOT, 6));
		setDown(server, connection, table.east(), 0.2, 0.2);
		Interaction gold = server.computeOnServer(s -> handlesIn(s.overworld(), table.east()).get(0));
		for (int i = 0; i < 5; i++) rightClick(server, connection, gold, true);
		hold(server, connection, new ItemStack(Items.CLOCK));
		setDown(server, connection, table.east(), 0.8, 0.2);
		hold(server, connection, new ItemStack(Items.HONEY_BOTTLE));
		setDown(server, connection, table.east(), 0.5, 0.8);

		// And a wall behind it: a sword hung aslant, a map, an enchanted book, a clock.
		BlockPos wall = table.north().above();
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(wall, Blocks.SPRUCE_PLANKS.defaultBlockState());
			s.overworld().setBlockAndUpdate(wall.east(), Blocks.SPRUCE_PLANKS.defaultBlockState());
		});
		hold(server, connection, new ItemStack(Items.IRON_SWORD));
		onFace(server, connection, wall, Direction.SOUTH, 0.5, 0.5);
		Interaction sword = handlesNear(server, wall).stream().filter(h -> h.getZ() > wall.getZ() + 1).findFirst().orElseThrow();
		hold(server, connection, ItemStack.EMPTY);
		for (int i = 0; i < 2; i++) rightClick(server, connection, sword, true);
		hold(server, connection, new ItemStack(Items.FILLED_MAP));
		onFace(server, connection, wall.east(), Direction.SOUTH, 0.25, 0.75);
		hold(server, connection, new ItemStack(Items.ENCHANTED_BOOK));
		onFace(server, connection, wall.east(), Direction.SOUTH, 0.75, 0.75);
		hold(server, connection, new ItemStack(Items.CLOCK));
		onFace(server, connection, wall.east(), Direction.SOUTH, 0.5, 0.25);

		server.runCommand("gamemode spectator @a");
		Vec3 middle = Vec3.atBottomCenterOf(table).add(0.5, 1, 0.5);
		Pictures.look(server, middle.add(0, 1.1, 1.8), middle);
		context.waitTicks(20);
		Pictures.frame(context, connection, "table");
	}

	/** Put the player here, to be within reach of what is being clicked. */
	private static void standAt(TestServerContext server, TestServerConnection connection, BlockPos at) {
		server.runOnServer(s -> connection.getServerPlayer().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
	}

	/** A kitchen corner for a picture: flowers on the table, things hanging from a beam above. */
	private static void larder(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos table) {
		BlockPos beam = table.above(3);
		server.runCommand("gamemode survival @a");
		standAt(server, connection, table.south(2));
		block(server, table, Blocks.SPRUCE_PLANKS);
		block(server, table.east(), Blocks.SPRUCE_PLANKS);
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(beam, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
			s.overworld().setBlockAndUpdate(beam.east(), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
		});
		hold(server, connection, new ItemStack(Items.POPPY));
		setDown(server, connection, table, 0.2, 0.8);
		hold(server, connection, new ItemStack(Items.CORNFLOWER));
		setDown(server, connection, table, 0.8, 0.8);
		hold(server, connection, new ItemStack(Items.BREAD));
		setDown(server, connection, table.east(), 0.5, 0.5);
		hold(server, connection, new ItemStack(Items.PORKCHOP));
		onFace(server, connection, beam, Direction.DOWN, 0.2, 0.5);
		hold(server, connection, new ItemStack(Items.WHEAT));
		onFace(server, connection, beam, Direction.DOWN, 0.8, 0.5);
		hold(server, connection, new ItemStack(Items.RABBIT));
		onFace(server, connection, beam.east(), Direction.DOWN, 0.2, 0.5);
		hold(server, connection, new ItemStack(Items.GLOW_BERRIES));
		onFace(server, connection, beam.east(), Direction.DOWN, 0.8, 0.5);

		server.runCommand("gamemode spectator @a");
		Vec3 middle = Vec3.atBottomCenterOf(table).add(0.5, 1, 0.5);
		Pictures.look(server, middle.add(0, 0.9, 3.2), middle.add(0, 0.8, 0));
		context.waitTicks(20);
		Pictures.frame(context, connection, "larder");
	}

	private static void block(TestServerContext server, BlockPos pos, net.minecraft.world.level.block.Block block) {
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(pos, block.defaultBlockState());
			s.overworld().setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
		});
	}

	private static void hold(TestServerContext server, TestServerConnection connection, ItemStack offHand) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			player.setItemInHand(InteractionHand.OFF_HAND, offHand);
		});
	}

	private static InteractionResult setDown(TestServerContext server, TestServerConnection connection, BlockPos block, double dx, double dz) {
		Vec3 at = new Vec3(block.getX() + dx, block.getY() + 1, block.getZ() + dz);
		return click(server, connection, new BlockHitResult(at, Direction.UP, block, false));
	}

	private static InteractionResult onFace(TestServerContext server, TestServerConnection connection, BlockPos block, Direction face, double across, double up) {
		Vec3 at = Vec3.atCenterOf(block).add(face.getUnitVec3().scale(0.5));
		at = switch (face.getAxis()) {
			case X -> new Vec3(at.x, block.getY() + up, block.getZ() + across);
			case Y -> new Vec3(block.getX() + across, at.y, block.getZ() + up);
			case Z -> new Vec3(block.getX() + across, block.getY() + up, at.z);
		};
		return click(server, connection, new BlockHitResult(at, face, block, false));
	}

	/** Kragle's glue, as it lands on a knack, without Kragle: an owner who is not the test's player. */
	private static void glue(TestServerContext server, Interaction handle) {
		server.runOnServer(s -> {
			handle.addTag("knick_knacks_kragled_" + java.util.UUID.randomUUID());
			handle.addTag("knick_knacks_kragled_by_Somebody");
			justfatlard.knick_knacks.Knack.refresh(s.overworld(), handle);
		});
	}

	/** Sneaking, main hand empty: the click as a vanilla client sends it, through every callback in front of it. */
	private static InteractionResult click(TestServerContext server, TestServerConnection connection, BlockHitResult hit) {
		return server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.setShiftKeyDown(true);
			InteractionResult result = player.gameMode.useItemOn(player, s.overworld(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
			player.setShiftKeyDown(false);
			return result;
		});
	}

	/** A right-click on a knack, as the interact packet a client sends; the result is whether anything still stands there changed. */
	private static InteractionResult rightClick(TestServerContext server, TestServerConnection connection, Interaction handle, boolean sneaking) {
		return server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			String before = describe(s.overworld(), handle);
			player.connection.handleInteract(new ServerboundInteractPacket(handle.getId(), InteractionHand.MAIN_HAND, Vec3.ZERO, sneaking));
			player.setShiftKeyDown(false);
			return before.equals(describe(s.overworld(), handle)) ? InteractionResult.PASS : InteractionResult.SUCCESS;
		});
	}

	private static void hit(TestServerContext server, TestServerConnection connection, Interaction handle, boolean sneaking) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.setShiftKeyDown(sneaking);
			player.connection.handleAttack(new ServerboundAttackPacket(handle.getId()));
			player.setShiftKeyDown(false);
		});
	}

	private static int offHandCount(TestServerContext server, TestServerConnection connection) {
		return server.computeOnServer(s -> connection.getServerPlayer().getOffhandItem().getCount());
	}

	private static List<Interaction> handles(TestServerContext server, BlockPos block) {
		return server.computeOnServer(s -> handlesIn(s.overworld(), block));
	}

	private static List<Interaction> handlesIn(ServerLevel level, BlockPos block) {
		return level.getEntitiesOfClass(Interaction.class, new AABB(block).expandTowards(0, 1.5, 0),
			e -> e.entityTags().contains("knick_knacks") && !e.isRemoved());
	}

	private static List<Interaction> handlesNear(TestServerContext server, BlockPos block) {
		return server.computeOnServer(s -> s.overworld().getEntitiesOfClass(Interaction.class, new AABB(block).inflate(1),
			e -> e.entityTags().contains("knick_knacks") && !e.isRemoved()));
	}

	private static String turnOfNear(ServerLevel level, Interaction handle) {
		for (Display.ItemDisplay e : level.getEntitiesOfClass(Display.ItemDisplay.class, handle.getBoundingBox().inflate(0.1),
				e -> e.position().distanceToSqr(handle.position()) < 1.0E-6)) {
			for (String tag : e.entityTags()) if (tag.startsWith("knick_knacks_turn_")) return tag;
		}
		return "none";
	}

	private static int pileSize(TestServerContext server, Interaction handle) {
		return server.computeOnServer(s -> (int) displaysIn(s.overworld(), handle.blockPosition().below()).stream()
			.filter(shown -> shown.position().distanceToSqr(handle.position()) < 1.0E-6).count());
	}

	private static List<Display.ItemDisplay> displaysIn(ServerLevel level, BlockPos block) {
		return level.getEntitiesOfClass(Display.ItemDisplay.class, new AABB(block).expandTowards(0, 1.5, 0),
			e -> e.entityTags().contains("knick_knacks") && !e.isRemoved());
	}

	/** Everything standing at a knack's point: what each display shows and its tags, to tell before from after. */
	private static String describe(ServerLevel level, Interaction handle) {
		if (handle.isRemoved()) return "gone";
		StringBuilder out = new StringBuilder();
		for (Display.ItemDisplay shown : level.getEntitiesOfClass(Display.ItemDisplay.class, handle.getBoundingBox().inflate(0.01),
				e -> e.entityTags().contains("knick_knacks") && !e.isRemoved() && e.position().distanceToSqr(handle.position()) < 1.0E-6)) {
			out.append(shown.getSlot(0).get()).append(shown.entityTags()).append(';');
		}
		return out + "h" + handle.getBbHeight();
	}

	private static String turnOf(ServerLevel level, Interaction handle) {
		for (Entity e : displaysIn(level, handle.blockPosition().below())) {
			if (e.position().distanceToSqr(handle.position()) > 1.0E-6) continue;
			for (String tag : e.entityTags()) if (tag.startsWith("knick_knacks_turn_")) return tag;
		}
		return "none";
	}

	private static String bitesShown(ServerLevel level, BlockPos block) {
		for (Display.ItemDisplay shown : displaysIn(level, block)) {
			CustomModelData data = shown.getSlot(0).get().get(DataComponents.CUSTOM_MODEL_DATA);
			if (data != null && !data.strings().isEmpty()) return data.strings().get(0);
		}
		return "none";
	}

	private static String showing(ServerLevel level, BlockPos block) {
		return displaysIn(level, block).stream()
			.map(shown -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(shown.getSlot(0).get().getItem()).toString())
			.sorted().reduce((a, b) -> a + " " + b).orElse("");
	}

	private static String corners(ServerLevel level, BlockPos block) {
		return handlesIn(level, block).stream()
			.map(e -> fmt(e.getX() - block.getX()) + "," + fmt(e.getZ() - block.getZ()))
			.sorted().reduce((a, b) -> a + " " + b).orElse("");
	}

	private static String fmt(double v) {
		return String.valueOf(Math.round(v * 100) / 100.0);
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
