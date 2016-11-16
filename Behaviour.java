import jdk.nashorn.internal.runtime.Debug;
import model.*;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * @author Nha
 *         Разбить лес на зоны, получать ближайшую зону и из нее nearestTree, чтобы можно было выходить из толпы не застревая в дереве
 *         Не бить лесных минионов если противник около нашего трона (Бейс рейс)
 *         Выбор линии: идти на мид предпочтительней, если там есть только 1 волшебник, идем на мид
 *         На миду, если мы ближайший к руне поц, идем к ней, при этом проверяя на пути деревья с помощью функции ВЛада
 */
public class Behaviour
{
    private static final boolean RANDOMIZE_WAYPOINTS_POSITION = true;
    private static final int WAYPOINTS_POSITIONS_OFFSET = 50;

    private static final int BONUS_SPAWN_INTERVAL = 2500;
    private static final int WAVE_SPAWN_INTERVAL = 750;

    private static final double WAYPOINT_RADIUS = 50.0;
    private static final double MINION_SPAWN_ZONE_RADIUS = 250.0;
    private static final int TICKS_UNTIL_STEPBACK = 200;

    // Описание мира
    private Wizard wizard;
    private World world;
    private Game game;
    private Move move;

    private ZoneManager zoneManager = new ZoneManager();
    private Zone currentZone;

    private int currentTick;

    private final Map<LaneType, Point[]> waypointsByLane = new EnumMap<>(LaneType.class);
    private Point[] minionSpawnPoints;
    private Point[] waypoints;

    private Random random;

    private double ticksToNextWave = WAVE_SPAWN_INTERVAL;
    private double ticksToNextBonus = BONUS_SPAWN_INTERVAL;

    // Персонаж
    private double preX, preY;
    private double preDangerLevel = -1, preLife = 0;
    private double lifeFactor = 1.0;
    private boolean isEmpowered, isHastened, isShielded, isFrozen, isBurning;

    // Цели
    private List<LivingUnit> nearestEnemies = new ArrayList<>(5);
    private List<LivingUnit> nearestFriends = new ArrayList<>(5);
    private LivingUnit nearestFriend, nearestFriendlyWizard;
    private LivingUnit nearestEnemy, weakestEnemy, weakestEnemyWizard;
    private Wizard nearestEnemyWizard;
    private LivingUnit nearestNeutral;

    private LivingUnit targetEnemy;

    public void handleTick(final Wizard wizard, final World world, final Game game, final Move move)
    {
        this.wizard = wizard;
        this.world = world;
        this.game = game;
        this.move = move;

        if (world.getTickIndex() - currentTick > 1)
        {
            waypoints = null;

            ticksToNextWave = WAVE_SPAWN_INTERVAL - (world.getTickIndex() - (world.getTickIndex() / WAVE_SPAWN_INTERVAL) * WAVE_SPAWN_INTERVAL);
            ticksToNextBonus = BONUS_SPAWN_INTERVAL - (world.getTickIndex() - (world.getTickIndex() / BONUS_SPAWN_INTERVAL) * BONUS_SPAWN_INTERVAL);
        }

        currentTick = world.getTickIndex();
        ticksToNextWave--;
        ticksToNextBonus--;
        if (currentTick % WAVE_SPAWN_INTERVAL == 0)
            ticksToNextWave = WAVE_SPAWN_INTERVAL;
        if (currentTick % BONUS_SPAWN_INTERVAL == 0)
            ticksToNextBonus = BONUS_SPAWN_INTERVAL;

        init();
        act();

        preX = wizard.getX();
        preY = wizard.getY();
        preLife = wizard.getLife();
    }

    private void act()
    {
        if (currentTick < 650)
            return;

        lifeFactor = getLifeFactor(wizard);
        checkStatuses();

        if (waypoints == null || currentTick == 750)
            chooseLane();

        currentZone = zoneManager.getZone(wizard);

        if (ticksToNextWave < TICKS_UNTIL_STEPBACK)
        {
            for (final Point spawnPoint : minionSpawnPoints)
            {
                if (wizard.getDistanceTo(spawnPoint.getX(), spawnPoint.getY()) <= MINION_SPAWN_ZONE_RADIUS)
                {
                    final Point previousPoint = getPreviousWaypoint();
                    if (nearestFriend != null && nearestFriend.getDistanceTo(wizard) < wizard.getCastRange() / 2.0 && nearestFriend.getDistanceTo(previousPoint.getX(), previousPoint.getY()) <= wizard.getDistanceTo(previousPoint.getX(), previousPoint.getY()))
                        moveTo(previousPoint, game.getWizardForwardSpeed());
                    else
                    {
                        strafeTo(previousPoint, 1.0);
                        tryToAttack();
                    }
                    return;
                }
            }
        }

        determinateTargets();

        DebugHelper.addLabel("targetEnemy", targetEnemy == null ? "null" : targetEnemy.getId());
        DebugHelper.pathTo(wizard, targetEnemy, Color.BLACK);
        DebugHelper.addLabel("nearestEnemy", nearestEnemy == null ? "null" : nearestEnemy.getId());
        DebugHelper.pathTo(wizard, nearestEnemy, Color.RED);

        if (targetEnemy == null)
        {
            moveTo(getNextWaypoint(), game.getWizardForwardSpeed());
        }
        else
        {
            if (lifeFactor < 0.2)
            {
                moveTo(getPreviousWaypoint(), game.getWizardForwardSpeed());
                return;
            }

            if (wizard.getRemainingCooldownTicksByAction()[2] > game.getMagicMissileCooldownTicks() / 2 && targetEnemy != weakestEnemyWizard)
                strafeTo(getPreviousWaypoint(), 1);
            else if (nearestFriend != null)
            {
                double wizardDistanceToNearestEnemy = wizard.getDistanceTo(nearestEnemy);
                List<LivingUnit> friendsInFrontOfMe = new ArrayList<>(nearestFriends.size());
                for (final LivingUnit friend : nearestFriends)
                {
                    if (friend.getDistanceTo(wizard) > wizard.getCastRange())
                        continue;
                    if (wizardDistanceToNearestEnemy > friend.getDistanceTo(nearestEnemy))
                        friendsInFrontOfMe.add(friend);
                }
                int count = friendsInFrontOfMe.size();

                if (count == 0 || (count == 1 && getLifeFactor(friendsInFrontOfMe.get(0)) <= 0.5))
                    strafeTo(getPreviousWaypoint(), 1);
                else
                    strafeTo(getNextWaypoint(), 1);
            }
            else
                strafeTo(getNextWaypoint(), 1);
        }

        tryToAttack();

//        if (isDanger() || (wizard.getRemainingCooldownTicksByAction()[2] > game.getMagicMissileCooldownTicks() / 2 && targetEnemy != weakestEnemyWizard))
//        {
//            DebugHelper.addLabel("Intention", "Go back");
//            if (lifeFactor < 0.2)
//            {
//                moveTo(getPreviousWaypoint(), game.getWizardForwardSpeed());
//                return;
//            }
//            strafeTo(getPreviousWaypoint(), 1);
//        }
//        else
//        {
//            DebugHelper.addLabel("Intention", "Move forward");
//            if (targetEnemy == null)
//                moveTo(getNextWaypoint(), game.getWizardForwardSpeed());
//            else
//                strafeTo(getNextWaypoint(), 1.0);
//        }
    }

    private void tryToAttack()
    {
        if (targetEnemy != null)
        {
            double distance = wizard.getDistanceTo(targetEnemy);
            if (distance <= wizard.getCastRange())
            {
                shootTo(targetEnemy);
            }
            else if (weakestEnemy != null && targetEnemy != weakestEnemy)
            {
                shootTo(weakestEnemy);
            }
            else if (nearestEnemy != null && targetEnemy != nearestEnemy)
            {
                shootTo(nearestEnemy);
            }
        }
    }

    private void shootTo(final LivingUnit unit)
    {
        double angle = wizard.getAngleTo(unit);
        move.setTurn(angle);

        if (StrictMath.abs(angle) < game.getStaffSector() / 2.0D)
        {
            double distance = wizard.getDistanceTo(unit);
            if (distance <= game.getStaffRange())
                move.setAction(ActionType.STAFF);
            else
            {
                move.setAction(ActionType.MAGIC_MISSILE);
                move.setCastAngle(angle);
                move.setMinCastDistance(distance - unit.getRadius() + game.getMagicMissileRadius());
            }
        }
    }

    private void strafeTo(final Point point, double speedMultiplier)
    {
        double angle = wizard.getAngle();
        double lookX = Math.cos(angle);
        double lookY = Math.sin(angle);

        double strafeX = lookY;
        double strafeY = -lookX;

        double destX = point.getX() - wizard.getX();
        double destY = point.getY() - wizard.getY();

        angle = Math.atan2(strafeX * destY - strafeY * destX, strafeX * destX + strafeY * destY);
        move.setStrafeSpeed(game.getWizardStrafeSpeed() * -Math.cos(angle) * speedMultiplier);

        angle = Math.atan2(lookX * destY - lookY * destX, lookX * destX + lookY * destY);
        move.setSpeed(game.getWizardForwardSpeed() * Math.cos(angle) * speedMultiplier);
    }

    private void moveTo(final Point point, double speed)
    {
        if (preX == wizard.getX() && preY == wizard.getY())
        {
            for (final LivingUnit friend : nearestFriends)
            {
                double distance = wizard.getDistanceTo(friend);
                if (distance - 10 <= wizard.getRadius() + friend.getRadius())
                {
                    final Point nextPoint = getNextWaypoint();
                    double angleToNextPoint = wizard.getAngleTo(nextPoint.getX(), nextPoint.getY());
                    double angleToFriend = wizard.getAngleTo(friend.getX(), friend.getY());
                    double sin = Math.sin(angleToFriend - angleToNextPoint);
                    int sign = sin > 0 ? -1 : 1;
                    move.setStrafeSpeed(game.getWizardStrafeSpeed() * sign);

                    return;
                }
            }
        }

        double angle = wizard.getAngleTo(point.getX(), point.getY());
        move.setTurn(angle);
        move.setSpeed(speed);
    }

//    private boolean isDanger()
//    {
//        if (targetEnemy == null)
//            return false;
//
//        if (nearestEnemy != null)
//        {
//
//            if (nearestEnemyWizard != null && nearestEnemyWizard != targetEnemy && nearestEnemyWizard.getDistanceTo(wizard) <= wizard.getCastRange())
//                return true;
//
//            double wizardDistanceToNearestEnemy = wizard.getDistanceTo(nearestEnemy);
//            List<LivingUnit> friendsInFrontOfMe = new ArrayList<>(nearestFriends.size());
//            for (final LivingUnit friend : nearestFriends)
//            {
//                if (friend.getDistanceTo(wizard) > wizard.getCastRange())
//                    continue;
//                double distance = friend.getDistanceTo(nearestEnemy);
//                if (wizardDistanceToNearestEnemy > distance)
//                    friendsInFrontOfMe.add(friend);
//            }
//            int count = friendsInFrontOfMe.size();
//
//            if (count == 0 && nearestEnemies.size() > 1)
//                return true;
//            if (count == 1 && targetEnemy == weakestEnemyWizard && targetEnemy.getLife() <= game.getMagicMissileDirectDamage())
//                return false;
//            if (count == 1 && getLifeFactor(friendsInFrontOfMe.get(0)) <= 0.5 && nearestEnemies.size() > 1)
//                return true;
//        }
//        return false;
//    }

    /*
    Можно оптимизировать для деревьев, разбив карту на зоны и добавить деревья в массив деревьев для каждой из зон
     */
    public List<LivingUnit> getObstaclesBetween(LivingUnit[] objects, final LivingUnit unitA, final LivingUnit unitB)
    {
        return getObstaclesBetween(objects, unitA, unitB.getX(), unitB.getY());
    }

    public List<LivingUnit> getObstaclesBetween(LivingUnit[] objects, final LivingUnit unitA, double px, double py)
    {
        final List<LivingUnit> obstacles = new ArrayList<>();
        double distance = unitA.getDistanceTo(px, py);
        for (LivingUnit obstacle : objects)
        {
            if (obstacle.getDistanceTo(px, py) < distance)
            {
                double x = px - unitA.getX();
                double y = py - unitA.getY();
                double area = Math.abs(x * (obstacle.getY() - unitA.getY()) - (obstacle.getX() - unitA.getX()) * y);
                double lengthAB = Math.sqrt(x * x + y * y);
                double h = area / lengthAB;

                if (h < obstacle.getRadius())
                    obstacles.add(obstacle);
            }
        }
        return obstacles;
    }

    public List<LivingUnit> getObstaclesBetween(List<LivingUnit> objects, final LivingUnit unitA, double px, double py)
    {
        final List<LivingUnit> obstacles = new ArrayList<>();
        double distance = unitA.getDistanceTo(px, py);
        for (LivingUnit obstacle : objects)
        {
            if (obstacle.getDistanceTo(px, py) < distance)
            {
                double x = px - unitA.getX();
                double y = py - unitA.getY();
                double area = Math.abs(x * (obstacle.getY() - unitA.getY()) - (obstacle.getX() - unitA.getX()) * y);
                double lengthAB = Math.sqrt(x * x + y * y);
                double h = area / lengthAB;

                if (h < obstacle.getRadius())
                    obstacles.add(obstacle);
            }
        }
        return obstacles;
    }

    private boolean isUnitALookingAtUnitB(final LivingUnit unitA, final LivingUnit unitB)
    {
        double angle = unitA.getAngleTo(unitB);
        return StrictMath.abs(angle) < game.getStaffSector() / 2.0D;
    }

    private double getLifeFactor(final LivingUnit unit)
    {
        return unit.getLife() / (double) unit.getMaxLife();
    }

    private void determinateTargets()
    {
        nearestEnemies.clear();
        nearestFriends.clear();

        targetEnemy = nearestEnemy = nearestEnemyWizard = null;
        nearestFriend = nearestFriendlyWizard = null;
        nearestNeutral = null;
        weakestEnemy = weakestEnemyWizard = null;

        final List<LivingUnit> targets = new ArrayList<>();
        targets.addAll(Arrays.asList(world.getBuildings()));
        targets.addAll(Arrays.asList(world.getWizards()));
        targets.addAll(Arrays.asList(world.getMinions()));

        double minFriendlyDistance = Double.MAX_VALUE;
        double minNeutralDistance = Double.MAX_VALUE;
        double minEnemyLife = Double.MAX_VALUE, minEnemyDistance = Double.MAX_VALUE;

        for (final LivingUnit unit : targets)
        {
            double distance = wizard.getDistanceTo(unit);
            if (distance > wizard.getVisionRange())
                continue;

            if (unit.getFaction() == wizard.getFaction())
            {
                if (unit.getId() == wizard.getId())
                    continue;

                nearestFriends.add(unit);
                if (distance < minFriendlyDistance)
                {
                    if (isUnitWizard(unit))
                        nearestFriendlyWizard = unit;
                    nearestFriend = unit;
                    minFriendlyDistance = distance;
                }
            }
            else if (unit.getFaction() == Faction.NEUTRAL)
            {
                if (distance < minNeutralDistance)
                {
                    nearestNeutral = unit;
                    minNeutralDistance = distance;
                }
            }
            else
            {
                nearestEnemies.add(unit);
                if (distance < minEnemyDistance)
                {
                    if (isUnitWizard(unit))
                        nearestEnemyWizard = (Wizard) unit;
                    nearestEnemy = unit;
                    minEnemyDistance = distance;
                }

                double enemyLife = unit.getLife();
                if (enemyLife < unit.getMaxLife() && enemyLife < minEnemyLife)
                {
                    if (isUnitWizard(unit))
                        weakestEnemyWizard = unit;
                    weakestEnemy = unit;
                    minEnemyLife = enemyLife;
                }
            }
        }
        //DebugHelper.circle(wizard.getX(), wizard.getY(), wizard.getCastRange(), Color.CYAN);
        if (nearestEnemy != null && nearestEnemy.getDistanceTo(wizard) <= wizard.getRadius() * 2.5)
        {
            targetEnemy = nearestEnemy;
            return;
        }

        if (weakestEnemyWizard != null)
        {
            double enemyHpFactor = getLifeFactor(weakestEnemyWizard);
            if (enemyHpFactor < 0.25 || enemyHpFactor < getLifeFactor(wizard))
            {
                targetEnemy = weakestEnemyWizard;
                return;
            }
        }

        if (nearestEnemyWizard != null && (isEmpowered || isShielded) && wizard.getDistanceTo(nearestEnemyWizard) <= wizard.getCastRange())
        {
            targetEnemy = nearestEnemyWizard;
            return;
        }

        if (weakestEnemy != null && weakestEnemy != weakestEnemyWizard && wizard.getDistanceTo(weakestEnemy) <= wizard.getCastRange())
        {
            targetEnemy = weakestEnemy;
            return;
        }

        if (nearestEnemyWizard != null && wizard.getDistanceTo(nearestEnemyWizard) <= wizard.getCastRange())
        {
            targetEnemy = nearestEnemyWizard;
            return;
        }

        if (nearestEnemy != null && wizard.getDistanceTo(nearestEnemy) <= wizard.getCastRange())
        {
            targetEnemy = nearestEnemy;
            return;
        }

        // Не атаковать нейтральных минионов находясь в зоне рун.
//        if (nearestNeutral != null && currentZone != null && currentZone.getId() != ZONE_BONUS_ROAD && wizard.getDistanceTo(nearestNeutral) <= wizard.getCastRange())
//        {
//            if (getObstaclesOnWay(world.getTrees(), wizard, nearestNeutral.getX(), nearestNeutral.getY()) == 0)
//                targetEnemy = nearestNeutral;
//            return;
//        }
    }

    private double distanceOnPath(CircularUnit unit, CircularUnit obstacle)
    {
        double angleToObstacle = Math.sin(unit.getAngleTo(obstacle));
        return angleToObstacle * unit.getDistanceTo(obstacle);
    }

    public int getObstaclesOnWay(final CircularUnit[] obstacles, final CircularUnit unit, double x, double y)
    {
        int obstaclesCount = 0;
        double dUtoP = unit.getDistanceTo(x, y);
        for (final CircularUnit o : obstacles)
        {
            double dOtoP = o.getDistanceTo(x, y);
            if (dOtoP < dUtoP)
            {
                double dOtoU = o.getDistanceTo(unit);
                double angle = Math.sin(getAngleBetween(x, y, o, unit));
                double h = angle * dOtoU;
                if (h <= o.getRadius() + unit.getRadius())
                    obstaclesCount++;
            }
        }
        return obstaclesCount;
    }

    public double getAngleBetween(double p0x, double p0y, final Unit p1, final Unit c)
    {
        double p0p1 = Math.pow(p1.getX() - p0x, 2) + Math.pow(p1.getY() - p0y, 2);
        double p2p1 = Math.pow(p1.getX() - c.getX(), 2) + Math.pow(p1.getY() - c.getY(), 2);
        double p0p2 = Math.pow(c.getX() - p0x, 2) + Math.pow(c.getY() - p0y, 2);
        return Math.acos((p2p1 + p0p1 - p0p2) / Math.sqrt(4 * p2p1 * p0p1));
    }

    private void checkStatuses()
    {
        isEmpowered = isHastened = isShielded = isFrozen = isBurning = false;
        for (final Status status : wizard.getStatuses())
        {
            switch (status.getType())
            {
                case EMPOWERED:
                    isEmpowered = true;
                    break;
                case HASTENED:
                    isHastened = true;
                    break;
                case SHIELDED:
                    isShielded = true;
                    break;
                case FROZEN:
                    isFrozen = true;
                    break;
                case BURNING:
                    isBurning = true;
                    break;
            }
        }
    }

    private void chooseLane()
    {
        LaneType targetLane;
        // Если это наш первый выбор линии, то выбираем наиболее свободную, предпочтительней - мид.
        if (currentTick <= 750)
            targetLane = getMostFreeLane();
        else
        {
            // Выбираем линию, где противники максимально ближе к нашей базе
            targetLane = getMostRiskyLane();
            // Если на всех линиях наша команда справляется хорошо, выбираем ту, где меньше союзных героев
            if (targetLane == null)
                targetLane = getMostFreeLane();
        }
        //DebugHelper.addLabel("Lane", targetLane);
        waypoints = waypointsByLane.get(targetLane);
    }

    private LaneType getMostRiskyLane()
    {
        final Zone top = zoneManager.getTop();
        final Zone bot = zoneManager.getBottom();
        final Zone mid = zoneManager.getMiddle();
        for (final Minion m : world.getMinions())
        {
            if (m.getFaction() == wizard.getFaction())
                continue;

            for (int i = top.getRadarsCount() - 1; i <= 0; i--)
            {
                if (mid.getRadar(i).isInRange(m))
                    return LaneType.MIDDLE;
                else if (top.getRadar(i).isInRange(m))
                    return LaneType.TOP;
                else if (bot.getRadar(i).isInRange(m))
                    return LaneType.BOTTOM;
            }
        }
        return null;
    }

    private LaneType getMostFreeLane()
    {
        int onMiddle = 0, onTop = 0, onBottom = 0;
        for (final Wizard w : world.getWizards())
        {
            if (w.getId() != wizard.getId() && w.getFaction() == wizard.getFaction())
            {
                final Zone zone = zoneManager.getZone(w);
                switch (zone.getId())
                {
                    case ZONE_MIDDLE:
                        onMiddle++;
                        break;
                    case ZONE_TOP:
                        onTop++;
                        break;
                    case ZONE_BOTTOM:
                        onBottom++;
                        break;
                }
            }
        }

        int min = Math.min(onMiddle, Math.min(onTop, onBottom));
        if (onMiddle == 1 || onMiddle == min)
            return LaneType.MIDDLE;

        return onTop == min ? LaneType.TOP : LaneType.BOTTOM;
    }

    private Point getNextWaypoint()
    {
        int lastWaypointIndex = waypoints.length - 1;
        Point lastWaypoint = waypoints[lastWaypointIndex];

        for (int waypointIndex = 0; waypointIndex < lastWaypointIndex; ++waypointIndex)
        {
            Point waypoint = waypoints[waypointIndex];

            if (waypoint.getDistanceTo(wizard) <= WAYPOINT_RADIUS)
            {
                return waypoints[waypointIndex + 1];
            }

            if (lastWaypoint.getDistanceTo(waypoint) < lastWaypoint.getDistanceTo(wizard))
            {
                return waypoint;
            }
        }

        return lastWaypoint;
    }

    private Point getPreviousWaypoint()
    {
        Point firstWaypoint = waypoints[0];

        for (int waypointIndex = waypoints.length - 1; waypointIndex > 0; --waypointIndex)
        {
            Point waypoint = waypoints[waypointIndex];

            if (waypoint.getDistanceTo(wizard) <= WAYPOINT_RADIUS)
            {
                return waypoints[waypointIndex - 1];
            }

            if (firstWaypoint.getDistanceTo(waypoint) < firstWaypoint.getDistanceTo(wizard))
            {
                return waypoint;
            }
        }

        return firstWaypoint;
    }

    private boolean isUnitWizard(final LivingUnit unit)
    {
        return unit.getRadius() == game.getWizardRadius();
    }

    private boolean isUnitBuilding(final LivingUnit unit)
    {
        return unit.getRadius() == game.getFactionBaseRadius() || unit.getRadius() == game.getGuardianTowerRadius();
    }

    private void init()
    {
        if (random == null)
        {
            random = new Random(game.getRandomSeed());

            final Point middleDown = new Point(600.0D, 3800);
            final Point middleUp = new Point(200.0D, 3400);

            waypointsByLane.put(LaneType.MIDDLE, new Point[]{new Point(100.0D, 3900), wizard.getDistanceTo(middleDown.getX(), middleDown.getY()) > wizard.getDistanceTo(middleUp.getX(), middleUp.getY()) ? middleUp : middleDown, new Point(800.0D, 3200), new Point(1200, 2800), new Point(1600, 2400), new Point(2000, 2000), new Point(2400, 1600), new Point(2800, 1200), new Point(3200, 800), new Point(3400, 600.0D), new Point(3800, 200.0D)});

            waypointsByLane.put(LaneType.TOP, new Point[]{new Point(100.0D, 3900), new Point(100.0D, 3600), new Point(200.0D, 3200), new Point(200, 3000), new Point(200, 2600), RANDOMIZE_WAYPOINTS_POSITION ? new Point(200 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET), 2200) : new Point(200, 2200), new Point(200, 1800), RANDOMIZE_WAYPOINTS_POSITION ? new Point(200 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET), 1400) : new Point(200, 1400), RANDOMIZE_WAYPOINTS_POSITION ? new Point(200 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET), 1000) : new Point(200, 1000), new Point(200, 600), new Point(400, 400), new Point(600, 200), RANDOMIZE_WAYPOINTS_POSITION ? new Point(1000, 200 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET)) : new Point(1000, 200), RANDOMIZE_WAYPOINTS_POSITION ? new Point(1400, 200 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET)) : new Point(1400, 200), RANDOMIZE_WAYPOINTS_POSITION ? new Point(1800, 200 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET)) : new Point(1800, 200), RANDOMIZE_WAYPOINTS_POSITION ? new Point(2200, 200 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET)) : new Point(2200, 200), RANDOMIZE_WAYPOINTS_POSITION ? new Point(2600, 200 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET)) : new Point(2600, 200), RANDOMIZE_WAYPOINTS_POSITION ? new Point(3000, 200 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET)) : new Point(3000, 200), new Point(3800, 200.0D)});

            waypointsByLane.put(LaneType.BOTTOM, new Point[]{new Point(100.0D, 3900), new Point(400.0D, 3900), new Point(800.0D, 3800), new Point(1000, 3800), new Point(1400, 3800), RANDOMIZE_WAYPOINTS_POSITION ? new Point(1800, 3800 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET)) : new Point(1800, 3800), new Point(2200, 3800), new Point(2600, 3800), RANDOMIZE_WAYPOINTS_POSITION ? new Point(3000, 3800 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET)) : new Point(3000, 3800), RANDOMIZE_WAYPOINTS_POSITION ? new Point(3400, 3800 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET)) : new Point(3400, 3800), new Point(3600, 3600), RANDOMIZE_WAYPOINTS_POSITION ? new Point(3800 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET), 3400) : new Point(3800, 3400), RANDOMIZE_WAYPOINTS_POSITION ? new Point(3800 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET), 3000) : new Point(3800, 3000), RANDOMIZE_WAYPOINTS_POSITION ? new Point(3800 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET), 2600) : new Point(3800, 2600), RANDOMIZE_WAYPOINTS_POSITION ? new Point(3800 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET), 2200) : new Point(3800, 2200), RANDOMIZE_WAYPOINTS_POSITION ? new Point(3800 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET), 1800) : new Point(3800, 1800), RANDOMIZE_WAYPOINTS_POSITION ? new Point(3800 + randInt(-WAYPOINTS_POSITIONS_OFFSET, WAYPOINTS_POSITIONS_OFFSET), 1400) : new Point(3800, 1400), new Point(3800, 1000), new Point(3800, 200.0D)});

            minionSpawnPoints = new Point[]{new Point(3200, 800), new Point(3000, 200), new Point(3800, 1000)};
        }
    }

    private int randInt(int min, int max)
    {
        return random.nextInt((max - min) + 1) + min;
    }

    public static final Behaviour getInstance()
    {
        return Behaviour.SingletonHolder._instance;
    }

    private static class SingletonHolder
    {
        protected static final Behaviour _instance = new Behaviour();
    }

    private final class Point
    {
        private double x;
        private double y;

        private Point(double x, double y)
        {
            this.x = x;
            this.y = y;
        }

        public double getX()
        {
            return x;
        }

        public double getY()
        {
            return y;
        }

        public double getDistanceTo(double x, double y)
        {
            return StrictMath.hypot(this.x - x, this.y - y);
        }

        public double getDistanceTo(Point point)
        {
            return getDistanceTo(point.x, point.y);
        }

        public double getDistanceTo(Unit unit)
        {
            return getDistanceTo(unit.getX(), unit.getY());
        }
    }

    private static final int ZONE_BASE = 0;
    private static final int ZONE_TOP = 1;
    private static final int ZONE_BOTTOM = 2;
    private static final int ZONE_MIDDLE = 3;
    private static final int ZONE_BONUS_ROAD = 4;
    private static final int ZONE_ENEMY_BASE = 5;

    private class ZoneManager
    {
        private final Point[] middleVertices = {new Point(400, 3200), new Point(800, 3600), new Point(3600, 800), new Point(3200, 400)};
        private final Point[] bonusRoadVertices = {new Point(400, 800), new Point(800, 400), new Point(3600, 3200), new Point(3200, 3600)};
        private final Point[] topVertices = {new Point(0, 3200), new Point(400, 3200), new Point(400, 800), new Point(800, 400), new Point(3200, 400), new Point(3200, 0), new Point(0, 0)};
        private final Point[] bottomVertices = {new Point(800, 3600), new Point(3200, 3600), new Point(3600, 3200), new Point(3600, 800), new Point(4000, 800), new Point(4000, 4000), new Point(800, 4000)};
        private final Point[] baseVertices = {new Point(0, 3200), new Point(400, 3200), new Point(800, 3600), new Point(800, 4000), new Point(0, 4000)};
        private final Point[] enemyBaseVertices = {new Point(3200, 0), new Point(4000, 0), new Point(4000, 800), new Point(3600, 800), new Point(3200, 400)};

        private final Radar[] middleRadars = {new Radar(1200, 2800, 600), new Radar(800, 3200, 500)};
        private final Radar[] topRadars = {new Radar(200, 1600, 600), new Radar(200, 2800, 400)};
        private final Radar[] bottomRadars = {new Radar(2400, 3800, 600), new Radar(1200, 3800, 500)};

        private Zone[] zones;

        public ZoneManager()
        {
            zones = new Zone[6];
            zones[0] = new Zone(ZONE_BASE, null, baseVertices);
            zones[1] = new Zone(ZONE_TOP, topRadars, topVertices);
            zones[2] = new Zone(ZONE_BOTTOM, bottomRadars, bottomVertices);
            zones[3] = new Zone(ZONE_MIDDLE, middleRadars, middleVertices);
            zones[4] = new Zone(ZONE_BONUS_ROAD, null, bonusRoadVertices);
            zones[5] = new Zone(ZONE_ENEMY_BASE, null, enemyBaseVertices);
        }

        public Zone getZone(final CircularUnit unit)
        {
            for (final Zone zone : zones)
            {
                if (zone.containsUnit(unit))
                    return zone;
            }
            return null;
        }

        public Zone getTop()
        {
            return zones[1];
        }

        public Zone getBottom()
        {
            return zones[2];
        }

        public Zone getMiddle()
        {
            return zones[3];
        }
    }

    private class Zone
    {
        private final int id;
        private final Radar[] radars;
        private final Point[] vertices;

        public Zone(int id, Radar[] radars, Point[] vertices)
        {
            this.id = id;
            this.radars = radars;
            this.vertices = vertices;
        }

        public boolean containsUnit(final CircularUnit unit)
        {
            return containsPoint(unit.getX(), unit.getY());
        }

        public boolean containsPoint(double x, double y)
        {
            boolean c = false;
            for (int i = 0, j = vertices.length - 1; i < vertices.length; j = i++)
            {
                if (((vertices[i].getY() > y) != (vertices[j].getY() > y)) && (x < (vertices[j].getX() - vertices[i].getX()) * (y - vertices[i].getY()) / (vertices[j].getY() - vertices[i].getY()) + vertices[i].getX()))
                    c = !c;
            }
            return c;
        }

        public int getId()
        {
            return id;
        }

        public int getRadarsCount()
        {
            return radars.length;
        }

        public Radar getRadar(int index)
        {
            return radars[index];
        }

        public Point[] getVertices()
        {
            return vertices;
        }
    }

    private class Radar
    {
        private final double x, y, radius;

        public Radar(double x, double y, double radius)
        {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }

        public double getX()
        {
            return x;
        }

        public double getY()
        {
            return y;
        }

        public double getRadius()
        {
            return radius;
        }

        public boolean isInRange(final Unit unit)
        {
            return unit.getDistanceTo(x, y) <= radius;
        }
    }
}
