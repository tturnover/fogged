package com.fogged.entity;

import com.fogged.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The fog lurker: a large underground worm that prowls beneath nearby players. It is a single logical
 * entity, but it keeps a {@linkplain #trailPoint trail of recent positions} so the renderer can draw a
 * chain of separate models -- head, several body-segment variants and a tail -- strung along its path.
 *
 * <p>It is a harmless phantom (invulnerable, {@code noPhysics} so it slides through blocks). While
 * docile it simply glides in a smooth circle around the nearest player, fully buried, surfacing only a
 * fin tip now and then. Break a block near it and it lunges up for one heavy hit, then retreats deep --
 * but it does not leave: it lingers for a random lifespan (at least 60s) before finally burrowing away.
 *
 * <p>The only client-relevant data are its position trail (for the body) and {@link #DATA_STATE} (so
 * the screen-shake effect can tell a lunge from a glide).
 */
public class FogLurker extends Monster {

    public enum State { DOCILE, LUNGE, DIVE, LEAVING }

    private static final EntityDataAccessor<Integer> DATA_STATE =
            SynchedEntityData.defineId(FogLurker.class, EntityDataSerializers.INT);
    // Blocks broken near it so far toward the attack threshold; synced so the client can escalate the
    // warning shake (each broken block doubles it).
    private static final EntityDataAccessor<Integer> DATA_DISTURBANCE =
            SynchedEntityData.defineId(FogLurker.class, EntityDataSerializers.INT);
    // Debug "frozen" spawn: no AI, no lifespan, body laid out straight so the model is fully visible.
    private static final EntityDataAccessor<Boolean> DATA_FROZEN =
            SynchedEntityData.defineId(FogLurker.class, EntityDataSerializers.BOOLEAN);

    private static final int PULSE_TICKS = 20; // one-second client shake pulse per block broken

    // Depths are measured from the ground surface (heightmap) -- or the player's own level when they are
    // below it (in a cave) -- never from the fog plane. Buried by BURROW_DEPTH, the solid blocks hide it.
    public static final double BURROW_DEPTH = 1.3;    // feet below the surface while cruising (fin/back shows)
    private static final double BREACH_DEPTH = 0.3;   // feet below the surface while breaching (rises further)
    private static final double LEAVE_DEPTH = 12.0;   // feet below the surface as it burrows away for good
    private static final int BREACH_PERIOD = 300;     // ticks between breaches (~15s)
    private static final int BREACH_LENGTH = 40;      // ticks a breach lasts (~2s)
    private static final int DIVE_TICKS = 45;         // length of the post-lunge follow-through dive
    private static final double DOCILE_SPEED = 0.16;
    private static final double LUNGE_SPEED = 0.72;
    private static final double DIG_SPEED = 0.18;     // max vertical climb speed while burrowing
    private static final double DIVE_FALL = 0.35;     // faster downward speed while arcing back into ground
    private static final double MAX_TURN = 0.08;      // radians/tick the heading may swing (lower = smoother)
    private static final double ORBIT_RADIUS = 7.0;   // how far out it circles its target player
    private static final double SMOOTH = 0.08;        // low-pass factor for the depth target (anti-jitter)

    // Minimum/extra lifespan in ticks: it sticks around at least 60s, up to 60s + 120s more.
    private static final int MIN_LIFESPAN = 60 * 20;
    private static final int EXTRA_LIFESPAN = 120 * 20;

    // Position history ring buffer (index 0 = newest), recorded every tick on both sides so the renderer
    // can place the body segments back along the path. Long enough to cover the whole body.
    private static final int TRAIL_LENGTH = 90;
    private final Vec3[] history = new Vec3[TRAIL_LENGTH];
    private int historyHead;   // index of the newest entry
    private int historyCount;  // how many entries are filled

    // --- server-only steering state (never synched: the client only needs the trail + DATA_STATE) ---
    private double heading;                 // current swim heading, radians (atan2(dz, dx))
    private double orbitAngle;              // current angle around the player
    private int orbitDir = 1;               // +1 / -1: which way it is circling (flips on re-roll)
    private double orbitRate = 1.0;         // angular-speed multiplier (varies on re-roll)
    private double orbitRadius = ORBIT_RADIUS; // current stand-off distance (varies on re-roll)
    private int prowlTimer;                 // ticks until the prowl params are re-rolled
    private double smoothBaseY = Double.NaN; // eased reference surface Y (kills per-block jitter)
    private double smoothDepth;             // eased burrow depth (smooth dives / surfacing)
    private int lifespanTicks;              // ticks left before it burrows away for good
    private int lungeTargetId = -1;
    private int lungeTicks;
    private int diveTicks;                  // counts the post-lunge follow-through dive
    private int surfaceTimer;               // drives the periodic breach cycle while docile
    private int disturbDecay;               // ticks until the disturbance count ebbs by one
    private boolean hasHit;

    // --- client-only: the one-second shake pulse fired when a fresh block break is synced down ---
    private int clientPrevDisturbance;
    private int pulseStart = -1000;         // client tickCount when the current pulse began

    public FogLurker(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.noPhysics = true;     // a phantom: slides through blocks
        this.setNoGravity(true);   // burrows under its own power instead of falling
        this.xpReward = 0;
        this.heading = this.random.nextDouble() * Math.PI * 2.0;
        this.orbitAngle = this.random.nextDouble() * Math.PI * 2.0;
        this.smoothDepth = BURROW_DEPTH;
        this.lifespanTicks = MIN_LIFESPAN + this.random.nextInt(EXTRA_LIFESPAN);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ATTACK_DAMAGE, 0.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATE, State.DOCILE.ordinal());
        builder.define(DATA_DISTURBANCE, 0);
        builder.define(DATA_FROZEN, false);
    }

    /** Debug frozen spawn: stops all AI/lifespan and lays the body out straight. */
    public boolean isFrozen() {
        return this.entityData.get(DATA_FROZEN);
    }

    public void setFrozen(boolean frozen) {
        this.entityData.set(DATA_FROZEN, frozen);
    }

    /** Blocks broken toward the attack so far. */
    public int disturbance() {
        return this.entityData.get(DATA_DISTURBANCE);
    }

    // Client: start a one-second shake pulse whenever the synced disturbance count rises (a new break).
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (level().isClientSide && DATA_DISTURBANCE.equals(key)) {
            int d = disturbance();
            if (d > clientPrevDisturbance) {
                pulseStart = this.tickCount;
            }
            clientPrevDisturbance = d;
        }
    }

    /** Client shake from the current break pulse: a one-second fade, the same strength for every block. */
    public float shakePulse(float partialTick) {
        float age = (this.tickCount - pulseStart) + partialTick;
        if (age < 0.0F || age >= PULSE_TICKS) {
            return 0.0F;
        }
        return 1.0F - age / PULSE_TICKS;
    }

    @Override
    protected void registerGoals() {
        // No vanilla goals: all movement is hand-rolled in customServerAiStep.
    }

    @Override
    public void tick() {
        super.tick();
        if (isFrozen()) {
            layStraightTrail(); // debug spawn: hold a fixed extended body so the whole model is visible
            return;
        }
        // Record the trail on both sides (client positions are interpolated, so the body glides too).
        historyHead = (historyHead - 1 + TRAIL_LENGTH) % TRAIL_LENGTH;
        history[historyHead] = position();
        if (historyCount < TRAIL_LENGTH) {
            historyCount++;
        }
        if (level().isClientSide) {
            emitBurrowParticles();
        }
    }

    // Fill the trail with a straight line running back from the head along its facing, so a motionless
    // (frozen) worm still renders as a full stretched body instead of collapsing every piece onto one point.
    private void layStraightTrail() {
        double yaw = Math.toRadians(getYRot() + 90.0);
        double bx = -Math.cos(yaw);
        double bz = -Math.sin(yaw);
        Vec3 head = position();
        for (int i = 0; i < TRAIL_LENGTH; i++) {
            history[i] = head.add(bx * i * 0.5, 0.0, bz * i * 0.5);
        }
        historyHead = 0;
        historyCount = TRAIL_LENGTH;
    }

    /**
     * Spew the crumbs of whatever blocks the worm is tunnelling through -- the same break particles you
     * get mining the block -- sampled along the body trail so the whole length churns the ground it
     * passes through. Client-only and skipped for any sample point that is in open air.
     */
    private void emitBurrowParticles() {
        // Sample a handful of points back along the body so crumbs trail the whole worm, not just the head.
        for (int i = 0; i < historyCount && i <= 36; i += 6) {
            Vec3 p = historyAt(i);
            BlockPos pos = BlockPos.containing(p.x, p.y, p.z);
            BlockState state = level().getBlockState(pos);
            if (!state.blocksMotion()) {
                continue; // in open air (cave / surfaced fin): nothing to break
            }
            BlockParticleOption opt = new BlockParticleOption(ParticleTypes.BLOCK, state).setPos(pos);
            for (int n = 0; n < 2; n++) {
                double ox = (random.nextDouble() - 0.5) * 1.2;
                double oy = (random.nextDouble() - 0.5) * 1.2;
                double oz = (random.nextDouble() - 0.5) * 1.2;
                level().addParticle(opt, p.x + ox, p.y + oy, p.z + oz,
                        (random.nextDouble() - 0.5) * 0.2, 0.15, (random.nextDouble() - 0.5) * 0.2);
            }
        }
    }

    public State state() {
        return State.values()[this.entityData.get(DATA_STATE)];
    }

    private void setState(State state) {
        this.entityData.set(DATA_STATE, state.ordinal());
    }

    /**
     * Position of the worm's body {@code delayTicks} ticks behind the head, interpolated by
     * {@code partialTick} on the same continuous clock the head renders on (the head is one minus
     * partialTick into the newest history step). Sampling every piece -- head included -- this way keeps
     * the whole chain on one sub-tick timeline, so nothing creeps forward-and-back between frames.
     */
    public Vec3 historyLerp(float partialTick, double delayTicks) {
        if (historyCount == 0) {
            return position();
        }
        double idx = (1.0 - partialTick) + delayTicks;
        int i = (int) Math.floor(idx);
        double frac = idx - i;
        return historyAt(i).lerp(historyAt(i + 1), frac);
    }

    private Vec3 historyAt(int i) {
        if (i < 0) {
            i = 0;
        } else if (i > historyCount - 1) {
            i = historyCount - 1;
        }
        Vec3 v = history[(historyHead + i) % TRAIL_LENGTH];
        return v != null ? v : position();
    }

    /**
     * Register a block broken near the worm. Each break ratchets up the disturbance (and the client's
     * warning shake); once it reaches lurkerBlocksToAttack the worm lunges. Only counts while docile.
     */
    public void disturb(Player player) {
        if (state() != State.DOCILE) {
            return;
        }
        int count = disturbance() + 1;
        this.entityData.set(DATA_DISTURBANCE, count); // always bump so the client fires a shake pulse
        disturbDecay = Config.LURKER_BLOCK_WINDOW.getAsInt() * 20;
        // A low stir from underground accompanies the shake, the same each block.
        level().playSound(null, getX(), getY(), getZ(),
                SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.6F, 0.6F);
        if (count >= Config.LURKER_BLOCKS_TO_ATTACK.getAsInt()) {
            provoke(player);
        }
    }

    /** Wake a docile lurker into a lunge at the given player. */
    public void provoke(Player player) {
        if (state() == State.LUNGE || state() == State.DIVE || state() == State.LEAVING) {
            return;
        }
        this.lungeTargetId = player.getId();
        this.lungeTicks = 0;
        this.hasHit = false;
        setState(State.LUNGE);
    }

    @Override
    protected void customServerAiStep() {
        if (isFrozen()) {
            return; // debug spawn: no movement, no lifespan countdown
        }
        // Its time is up: stop everything else and burrow away, then vanish once deep enough.
        if (--lifespanTicks <= 0 && state() != State.LEAVING) {
            setState(State.LEAVING);
        }

        switch (state()) {
            case DOCILE -> tickDocile();
            case LUNGE -> tickLunge();
            case DIVE -> tickDive();
            case LEAVING -> tickLeaving();
        }
    }

    private void tickDocile() {
        Player target = nearestDeepPlayer();
        if (target == null) {
            // The player it stalks has risen above the spawn depth (or left range): leave quietly. This
            // only happens here in the peaceful state -- a lunge or dive in progress is never interrupted.
            setState(State.LEAVING);
            return;
        }

        // Disturbance ebbs away if no blocks are broken within the window, so it takes a sustained dig.
        if (disturbance() > 0 && --disturbDecay <= 0) {
            this.entityData.set(DATA_DISTURBANCE, disturbance() - 1);
            disturbDecay = Config.LURKER_BLOCK_WINDOW.getAsInt() * 20;
        }

        // Prowl around the player rather than tracing a fixed circle: every few seconds re-roll the
        // direction, angular speed and stand-off distance, and breathe the radius in and out. The
        // limited turn rate in swimToward keeps these changes smooth.
        if (--prowlTimer <= 0) {
            orbitDir = random.nextBoolean() ? 1 : -1;
            orbitRate = 0.6 + random.nextDouble();          // 0.6 .. 1.6
            orbitRadius = 3.5 + random.nextDouble() * 6.5;  // 3.5 .. 10
            prowlTimer = 60 + random.nextInt(140);          // 3 .. 10 s
        }
        orbitAngle += 0.02 * orbitDir * orbitRate;
        double radius = orbitRadius + Math.sin(tickCount * 0.03) * 1.8; // ease nearer / further
        double tx = target.getX() + Math.cos(orbitAngle) * radius;
        double tz = target.getZ() + Math.sin(orbitAngle) * radius;

        // Bury under the nearest SOLID floor at the worm's own column, scanning down from just above the
        // player's level. In caves this sinks it into the floor instead of leaving it hanging in open air
        // a fixed offset below the player. Sampled at the worm's own (smoothly moving) position, then eased.
        double startY = Math.min(groundTop(getX(), getZ()), target.getY()) + 2.0;
        double baseY = solidSurfaceY(getX(), getZ(), startY);

        // Ease the reference surface and the burrow depth so vertical motion is a smooth glide, and only
        // surface a fin tip on the rare breach window.
        smoothBaseY = Double.isNaN(smoothBaseY) ? baseY : smoothBaseY + (baseY - smoothBaseY) * SMOOTH;
        boolean breaching = (++surfaceTimer % BREACH_PERIOD) < BREACH_LENGTH;
        double targetDepth = breaching ? BREACH_DEPTH : BURROW_DEPTH;
        smoothDepth += (targetDepth - smoothDepth) * SMOOTH;

        swimToward(tx, tz, smoothBaseY - smoothDepth, DOCILE_SPEED);
    }

    private void tickLunge() {
        Entity t = level().getEntity(lungeTargetId);
        if (!(t instanceof Player p) || p.isRemoved() || p.isCreative() || p.isSpectator() || ++lungeTicks > 70) {
            beginDive(); // missed / timed out: still follow through forward, never reverse
            return;
        }
        // Burst up out of the blocks straight at the player.
        dartToward(p.getX(), p.getEyeY() - 0.2, p.getZ(), LUNGE_SPEED);
        if (!hasHit && this.distanceTo(p) < 1.8) {
            hasHit = true;
            double dmg = Config.LURKER_LUNGE_DAMAGE.get();
            if (dmg > 0.0) {
                p.hurt(com.fogged.ModDamageTypes.fogLurker(level(), this), (float) dmg);
            }
            beginDive(); // carry the momentum through and over the player, not back the way it came
        }
    }

    private void tickDive() {
        // Follow-through: keep the lunge heading (no turning, no reversing) and arc back down into the
        // ground, decelerating from lunge speed to a glide, then resume prowling.
        double ty = solidSurfaceY(getX(), getZ(), getY()) - BURROW_DEPTH;
        double speed = Mth.lerp(Math.min(1.0, diveTicks / (double) DIVE_TICKS), LUNGE_SPEED, DOCILE_SPEED);
        double vy = Mth.clamp(ty - getY(), -DIVE_FALL, DIG_SPEED);
        setDeltaMovement(Math.cos(heading) * speed, vy, Math.sin(heading) * speed);
        faceHeading();
        if (++diveTicks > DIVE_TICKS || getY() <= ty + 0.5) {
            smoothDepth = Math.max(BURROW_DEPTH, baseSurfaceY() - getY()); // ease back up from here
            this.entityData.set(DATA_DISTURBANCE, 0); // fresh count for the next disturbance
            setState(State.DOCILE);
        }
    }

    // Current eased surface reference, falling back to the live ground when not yet primed.
    private double baseSurfaceY() {
        return Double.isNaN(smoothBaseY) ? groundTop(getX(), getZ()) : smoothBaseY;
    }

    private void tickLeaving() {
        // Burrow straight down and away until well below the floor, then remove it.
        double ty = solidSurfaceY(getX(), getZ(), getY()) - LEAVE_DEPTH;
        setDeltaMovement(Math.cos(heading) * DOCILE_SPEED,
                Mth.clamp(ty - getY(), -DIG_SPEED, DIG_SPEED),
                Math.sin(heading) * DOCILE_SPEED);
        faceHeading();
        if (getY() <= ty + 1.0) {
            discard();
        }
    }

    // Y of the open-sky ground surface (top of the highest non-air block) at a world x/z.
    private double groundTop(double x, double z) {
        return level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
    }

    // Top face of the first solid (motion-blocking) block found scanning DOWN from startY at this column.
    // Unlike the heightmap this respects caves -- it finds the actual floor the worm should bury beneath,
    // so it never hangs in open cavern air. Scan is bounded so a deep shaft can't make it expensive.
    private double solidSurfaceY(double x, double z, double startY) {
        int xi = Mth.floor(x);
        int zi = Mth.floor(z);
        int top = Mth.floor(startY);
        int bottom = Math.max(level().getMinBuildHeight(), top - 64);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = top; y >= bottom; y--) {
            pos.set(xi, y, zi);
            if (level().getBlockState(pos).blocksMotion()) {
                return y + 1; // surface = the top face of that solid block
            }
        }
        return bottom;
    }

    private void beginDive() {
        this.diveTicks = 0;
        setState(State.DIVE); // keep the current heading so it carries forward through the arc
    }

    // Swim toward a point: always move forward at swim speed, turning the heading only a little each tick
    // so the worm carves smooth arcs. Vertical motion eases toward the target depth.
    private void swimToward(double tx, double tz, double ty, double speed) {
        double desired = Mth.atan2(tz - getZ(), tx - getX());
        heading = turnToward(heading, desired, MAX_TURN);
        double vy = Mth.clamp(ty - getY(), -DIG_SPEED, DIG_SPEED);
        setDeltaMovement(Math.cos(heading) * speed, vy, Math.sin(heading) * speed);
        faceHeading();
    }

    // A straight, fast dash toward a 3-D point (used for the lunge): snap onto the heading and go.
    private void dartToward(double tx, double ty, double tz, double speed) {
        double dx = tx - getX();
        double dz = tz - getZ();
        if (dx * dx + dz * dz > 1.0e-6) {
            heading = Mth.atan2(dz, dx);
        }
        double vy = Mth.clamp(ty - getY(), -speed, speed);
        setDeltaMovement(Math.cos(heading) * speed, vy, Math.sin(heading) * speed);
        faceHeading();
    }

    private void faceHeading() {
        float yaw = (float) Math.toDegrees(heading) - 90.0F;
        setYRot(yaw);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
    }

    // Turn `cur` toward `target` (both radians) by at most `maxStep`, taking the shortest way round.
    private static double turnToward(double cur, double target, double maxStep) {
        double diff = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(target - cur)));
        return cur + Mth.clamp(diff, -maxStep, maxStep);
    }

    // Nearest non-spectator player within range who is still at least lurkerMinDepth below the fog plane
    // (the depth it was gated to spawn under). When none qualify -- the player has risen above its spawn
    // height -- the docile worm leaves. The lunge/dive states ignore this and finish their attack.
    private Player nearestDeepPlayer() {
        double range = Config.LURKER_RANGE.get();
        double best = range * range;
        double surfaceY = Config.breathHeight(level()) + Config.PLANE_SURFACE_OFFSET;
        int minDepth = Config.LURKER_MIN_DEPTH.getAsInt();
        Player found = null;
        for (Player p : level().players()) {
            if (p.isSpectator() || surfaceY - p.getEyeY() < minDepth) {
                continue;
            }
            double d = p.distanceToSqr(this);
            if (d < best) {
                best = d;
                found = p;
            }
        }
        return found;
    }

    // --- a harmless phantom: cannot be hurt, pushed, looted or naturally despawned ---

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
        // phantom: never shoves anything
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false; // its lifespan, not distance, decides when it leaves
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean shouldDespawnInPeaceful() {
        return true;
    }
}
