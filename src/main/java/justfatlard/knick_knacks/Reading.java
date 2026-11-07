package justfatlard.knick_knacks;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A book set down opens the way one on a lectern does: the lectern's own screen, which every
 * client already has, turning pages and all. Its Take Book button picks the book up.
 */
final class Reading {
	private Reading() {}

	private static final double REACH_SQUARED = 8 * 8;

	/** Opens the book on top of the pile; false if it is not a book with pages. */
	static boolean open(ServerPlayer player, ServerLevel level, Knack.Pile pile) {
		Display.ItemDisplay shown = pile.top();
		ItemStack book = shown.getSlot(0).get();
		if (!book.is(Items.WRITTEN_BOOK) && !book.is(Items.WRITABLE_BOOK)) return false;

		Container page = new SimpleContainer(book.copy()) {
			@Override
			public ItemStack removeItemNoUpdate(int slot) {
				if (shown.isRemoved() || !Glue.allows(player, pile.handle())) return ItemStack.EMPTY;
				Knack.takeOff(level, pile.handle(), shown);
				return super.removeItemNoUpdate(slot);
			}

			@Override
			public boolean stillValid(Player reader) {
				return !shown.isRemoved() && reader.distanceToSqr(shown.position()) <= REACH_SQUARED;
			}
		};
		player.openMenu(new SimpleMenuProvider((id, inventory, reader) -> new LecternMenu(id, page, new SimpleContainerData(1)),
			book.getHoverName()));
		return true;
	}
}
