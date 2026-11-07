package justfatlard.knick_knacks.mixin;

import justfatlard.knick_knacks.Burst;
import justfatlard.knick_knacks.Knack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An arrow or a snowball that meets a bottle set down breaks it and flies on through where it was,
 * rather than hitting it the way it would hit a mob. Caught where every projectile hands over what
 * it hit, before anything is done about it.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
	@Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
	private void knickKnacks$shatter(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> cir) {
		if (!(hit instanceof EntityHitResult entityHit) || !(entityHit.getEntity() instanceof Interaction handle)) return;
		if (!Knack.isKnack(handle) || !Burst.isBrittle(handle)) return;
		Projectile self = (Projectile) (Object) this;
		if (self.level() instanceof ServerLevel level) Burst.shatter(level, handle, self.getOwner());
		cir.setReturnValue(ProjectileDeflection.NONE);
	}
}
