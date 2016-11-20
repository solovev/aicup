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

    private LaneType currentLane;
    private Point[] waypoints;

    private Random random;

    private double ticksToNextWave = WAVE_SPAWN_INTERVAL;
    private double ticksToNextBonus = BONUS_SPAWN_INTERVAL;
    private int topBonusState = 1;
    private int bottomBonusState = 1;

    // Персонаж
    private double preX, preY;
    private double preDangerLevel = -1, preLife = 0;
    private double lifeFactor = 1.0;
    private boolean isEmpowered, isHastened, isShielded, isFrozen, isBurning;
    private Point interestingBonusLocation;

    // Цели
    private List<LivingUnit> nearestEnemies = new ArrayList<>(5);
    private List<LivingUnit> nearestFriends = new ArrayList<>(5);
    private LivingUnit nearestFriend, nearestFriendlyWizard;
    private LivingUnit nearestEnemy, weakestEnemy, weakestEnemyWizard;
    private Wizard nearestEnemyWizard;
    private LivingUnit nearestNeutral;

    private LivingUnit targetEnemy;
    private boolean isTargetEmpowered, isTargetHastened, isTargetShielded, isTargetFrozen, isTargetBurning;

    // Другое
    private Point tempPoint;

    public void handleTick(final Wizard wizard, final World world, final Game game, final Move move)
    {
        this.wizard = wizard;
        this.world = world;
        this.game = game;
        this.move = move;

        tempPoint = new Point(0, 0);

        if (world.getTickIndex() - currentTick > 1)
        {
            waypoints = null;
            currentLane = null;

            ticksToNextWave = WAVE_SPAWN_INTERVAL - (world.getTickIndex() - (world.getTickIndex() / WAVE_SPAWN_INTERVAL) * WAVE_SPAWN_INTERVAL);
            ticksToNextBonus = BONUS_SPAWN_INTERVAL - (world.getTickIndex() - (world.getTickIndex() / BONUS_SPAWN_INTERVAL) * BONUS_SPAWN_INTERVAL);
        }

        currentTick = world.getTickIndex();

        ticksToNextWave--;
        ticksToNextBonus--;
        if (currentTick % WAVE_SPAWN_INTERVAL == 0)
            ticksToNextWave = WAVE_SPAWN_INTERVAL;
        if (currentTick % BONUS_SPAWN_INTERVAL == 0)
        {
            topBonusState = 0;
            bottomBonusState = 0;
            ticksToNextBonus = BONUS_SPAWN_INTERVAL;
        }

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

        determinateTargets();
        targetEnemy = getRelevantTarget();
        checkTargetStatuses();

        if (targetEnemy != null)
            move.setTurn(wizard.getAngleTo(targetEnemy));

        DebugHelper.addLabel("targetEnemy", targetEnemy == null ? "null" : targetEnemy.getId());
        DebugHelper.pathTo(wizard, targetEnemy, Color.RED);
        DebugHelper.addLabel("nearestEnemy", nearestEnemy == null ? "null" : nearestEnemy.getId());
        DebugHelper.pathTo(wizard, nearestEnemy, Color.CYAN);

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
                        moveBackTo(previousPoint, 1.0);
                        attack();
                    }
                    return;
                }
            }
        }

        if (currentTick >= 2200 && currentZone != null && currentZone.getId() != ZONE_BASE && currentZone.getId() != ZONE_ENEMY_BASE)
        {
            final Point laneCenter = getCurrentLaneCenter();
            double distanceToCenter = wizard.getDistanceTo(laneCenter.getX(), laneCenter.getY());

            final boolean cond1 = distanceToCenter <= 425 && nearestFriends.size() > nearestEnemies.size();
            final boolean cond2 = distanceToCenter <= 425 && nearestEnemies.size() == 1 && isUnitBuilding(nearestEnemies.get(0));
            final boolean cond3 = targetEnemy != null && zoneManager.getZone(targetEnemy) != currentZone && currentZone.getId() == ZONE_BONUS_ROAD;
            final boolean cond4 = currentZone != null && currentZone.getId() == ZONE_BONUS_ROAD;
            DebugHelper.addLabel("BONUS", "-");
            if (cond3 || cond4 || cond1 || cond2)
            {
                DebugHelper.addLabel("COND", interestingBonusLocation + " " + "AAAAAAAAAAAAAAAAAAAAAAAAA");
                checkBonusesState();
                final Point bonusLocation = getBonusLocation();
                DebugHelper.addLabel("BONUS", bonusLocation != null ? (bonusLocation.getX()) : "null");

                if (bonusLocation != null)
                    interestingBonusLocation = getReachablePoint(bonusLocation);
                else
                    interestingBonusLocation = null;

                if (interestingBonusLocation != null && cond4)
                {
                    if (nearestEnemyWizard != null && zoneManager.getZone(nearestEnemyWizard) == currentZone)
                    {
                        final double distanceFromEnemy = nearestEnemyWizard.getDistanceTo(interestingBonusLocation.getX(), interestingBonusLocation.getY());
                        final double distanceFromWizard = wizard.getDistanceTo(interestingBonusLocation.getX(), interestingBonusLocation.getY());

                        if (distanceFromWizard < distanceFromEnemy)
                            interestingBonusLocation = null;
                    }
                }
            }
            else
            {
                interestingBonusLocation = null;
            }
        }

        if (interestingBonusLocation != null)
        {
            moveAndLookToEnemy(interestingBonusLocation);
            attack();
            DebugHelper.addLabel("COND", interestingBonusLocation + " " + "CCCCCCCCCCCCCCCCCCCCCCCCC");
            return;
        }

        if (currentZone != null && currentZone.getId() == ZONE_BONUS_ROAD)
        {
            if (targetEnemy == null)
            {
                moveTo(getRelevantLanePoint(), game.getWizardForwardSpeed());
            }
            else
            {
                if (zoneManager.getZone(targetEnemy) == currentZone)
                {
                    if (targetEnemy == nearestEnemyWizard && nearestEnemies.size() == 1)
                    {
                        if (isTargetEmpowered || isTargetShielded || (lifeFactor < getLifeFactor(targetEnemy) && !isShielded && !isEmpowered))
                        {
                            strafeTo(getRelevantLanePoint(), game.getWizardForwardSpeed());
                        }
                        else
                        {
                            final double distanceToEnemy = wizard.getDistanceTo(targetEnemy);
                            if (distanceToEnemy > game.getStaffRange())
                                moveTo(targetEnemy, game.getWizardForwardSpeed());
                        }

                    }
                    else
                    {
                        strafeTo(getRelevantLanePoint(), game.getWizardForwardSpeed());
                    }
                }
                else
                {
                    strafeTo(getRelevantLanePoint(), game.getWizardForwardSpeed());
                }
            }
        }
        else
        {
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

                if (lifeFactor <= 0.75)
                    moveBackTo(getPreviousWaypoint(), 1.0);

                else if (nearestFriends.size() < nearestEnemies.size() && nearestEnemies.size() > 2 && wizard.getDistanceTo(targetEnemy) < wizard.getCastRange())
                    moveBackTo(getPreviousWaypoint(), 1);
                else if (nearestFriends.size() < nearestEnemies.size() && nearestEnemies.size() == 2 && wizard.getDistanceTo(targetEnemy) < wizard.getCastRange() * 0.75)
                    moveBackTo(getPreviousWaypoint(), 1);
                else if (nearestFriends.size() < nearestEnemies.size() && nearestEnemies.size() == 1 && wizard.getDistanceTo(targetEnemy) < wizard.getCastRange() * 0.6)
                    moveBackTo(getPreviousWaypoint(), 1);
                else if (targetEnemy == weakestEnemy && wizard.getDistanceTo(targetEnemy) < wizard.getCastRange() * 0.8)
                    moveBackTo(getPreviousWaypoint(), 1);

                else if (lifeFactor != 1.0 && nearestEnemyWizard != null && targetEnemy != nearestEnemyWizard && nearestEnemyWizard.getDistanceTo(wizard) <= nearestEnemyWizard.getCastRange())
                    moveBackTo(getPreviousWaypoint(), 1);
                else if (nearestFriend != null && nearestEnemy != null)
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

                    if (count == 1 && getLifeFactor(friendsInFrontOfMe.get(0)) <= 0.5)
                    {
                        if (targetEnemy == weakestEnemyWizard && getLifeFactor(targetEnemy) < 0.1)
                            moveTo(targetEnemy, game.getWizardForwardSpeed());
                        else
                            moveBackTo(getPreviousWaypoint(), 1);
                    }
                    else if (count == 0)
                    {
                        if (nearestEnemies.size() > 1)
                            moveBackTo(getPreviousWaypoint(), 1);
                        else if (nearestEnemies.size() == 0)
                            strafeTo(getNextWaypoint(), 0.5);
                        else if (getLifeFactor(nearestEnemy) < lifeFactor)
                            moveTo(nearestEnemy, game.getWizardForwardSpeed());
                    }
                    else if (wizardDistanceToNearestEnemy > wizard.getCastRange() / 2.0)
                        strafeTo(getNextWaypoint(), 1);
                }
                else
                    strafeTo(getNextWaypoint(), 1);
            }
        }
        attack();
    }

    private Point getRelevantLanePoint()
    {
        final Point prePoint = getPreviousWaypoint();
        final Point centerPoint = getCurrentLaneCenter();
        Point destination = getReachablePoint(centerPoint, prePoint, getNextWaypoint());
        if (destination == null)
        {
            tempPoint.setX((centerPoint.getX() + prePoint.getX()) / 2);
            tempPoint.setY((centerPoint.getY() + prePoint.getY()) / 2);
            destination = tempPoint;
        }
        return destination;
    }

    private Point getReachablePoint(Point ...points)
    {
        for (final Point point : points)
        {
            final List<CircularUnit> obstacles = new ArrayList<>(64);
            for (final Tree tree : world.getTrees())
            {
                if ((wizard.getX() < 2000 && (tree.getX() < 2000 || tree.getY() < 2000)) || (wizard.getX() > 2000 && (tree.getX() > 2000 || tree.getY() > 2000)))
                    obstacles.add(tree);
            }
            obstacles.addAll(nearestEnemies);
            obstacles.addAll(nearestFriends);

            int obstaclesOnWay = getObstaclesOnWay(obstacles, wizard, point.getX(), point.getY());

            if (obstaclesOnWay == 0)
                return point;
        }
        return null;
    }

    private Point getBonusLocation()
    {
        if (currentLane == LaneType.MIDDLE)
        {
            if (bottomBonusState != 2)
                return zoneManager.getBottomBonusLocation();
            if (topBonusState != 2)
                return zoneManager.getTopBonusLocation();
            else
                return null;
        }
        else if (currentLane == LaneType.TOP && topBonusState != 2)
            return zoneManager.getTopBonusLocation();
        else if (currentLane == LaneType.BOTTOM && bottomBonusState != 2)
            return zoneManager.getBottomBonusLocation();
        else
            return null;
    }

    private void checkBonusesState()
    {
        final Point topBonusLocation = zoneManager.getTopBonusLocation();
        final Point bottomBonusLocation = zoneManager.getBottomBonusLocation();
        if (wizard.getDistanceTo(topBonusLocation.getX(), topBonusLocation.getY()) <= wizard.getVisionRange())
        {
            for (final Bonus bonus : world.getBonuses())
            {
                if (wizard.getDistanceTo(bonus) <= wizard.getVisionRange())
                {
                    topBonusState = 1;
                    return;
                }
            }
            topBonusState = 2;
        }
        else if (wizard.getDistanceTo(bottomBonusLocation.getX(), bottomBonusLocation.getY()) <= wizard.getVisionRange())
        {
            for (final Bonus bonus : world.getBonuses())
            {
                if (wizard.getDistanceTo(bonus) <= wizard.getVisionRange())
                {
                    bottomBonusState = 1;
                    return;
                }
            }
            bottomBonusState = 2;
        }
    }

    private Point getCurrentLaneCenter()
    {
        Point laneCenter = zoneManager.getMiddle().getCenter();
        if (currentLane != null)
        {
            switch (currentLane)
            {
                case TOP:
                    laneCenter = zoneManager.getTop().getCenter();
                    break;
                case BOTTOM:
                    laneCenter = zoneManager.getBottom().getCenter();
                    break;
            }
        }
        return laneCenter;
    }

    private void moveAndLookToEnemy(final Point point)
    {
        if (nearestEnemy != null)
            strafeTo(point, 1.0);
        else
            moveTo(point, game.getWizardForwardSpeed());
    }

    private void moveTo(final Point point, double speed)
    {
        moveTo(point.getX(), point.getY(), speed);
    }

    private void moveTo(final Unit unit, double speed)
    {
        moveTo(unit.getX(), unit.getY(), speed);
    }

    private void moveTo(double x, double y, double speed)
    {
        if (!circumventObstacle())
        {
            double angle = wizard.getAngleTo(x, y);
            move.setTurn(angle);
            move.setSpeed(speed);
        }
    }

    private void moveBackTo(final Point point, double speedMultiplier)
    {
        double angle = wizard.getAngleTo(point.getX(), point.getY()) - 3.14159;
        if (StrictMath.abs(angle) < game.getStaffSector())
        {
            if (!circumventObstacle())
                move.setSpeed(-game.getWizardBackwardSpeed() * speedMultiplier);
        }
        else
            strafeTo(point, speedMultiplier);
    }

    private boolean circumventObstacle()
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

                    return true;
                }
            }
        }
        return false;
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

    private void attack()
    {
        final List<LivingUnit> targets = new ArrayList<>();
        if (targetEnemy != null)
            targets.add(targetEnemy);
        if (weakestEnemy != null)
            targets.add(weakestEnemy);
        if (weakestEnemyWizard != null && weakestEnemyWizard != weakestEnemy)
            targets.add(weakestEnemyWizard);
        if (nearestEnemyWizard != null && nearestEnemyWizard != nearestEnemy)
            targets.add(nearestEnemyWizard);
        if (nearestEnemy != null)
            targets.add(nearestEnemy);
        if (nearestNeutral != null && currentZone != null && currentZone.getId() != ZONE_BONUS_ROAD)
            targets.add(nearestNeutral);

        for (final LivingUnit target : targets)
        {
            double distance = wizard.getDistanceTo(target);
            double angle = wizard.getAngleTo(target);
            if (distance > wizard.getCastRange() || StrictMath.abs(angle) > game.getStaffSector() / 2.0D)
                continue;

            if (distance <= game.getStaffRange() + target.getRadius() && wizard.getRemainingCooldownTicksByAction()[2] != 0)
                move.setAction(ActionType.STAFF);
            else
            {
                move.setAction(ActionType.MAGIC_MISSILE);
                move.setCastAngle(angle);
                move.setMinCastDistance(distance - target.getRadius() + game.getMagicMissileRadius());
            }
            break;
        }
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
    }

    private LivingUnit getRelevantTarget()
    {
        if (nearestEnemyWizard != null && currentZone != null && currentZone.getId() == ZONE_BONUS_ROAD && zoneManager.getZone(nearestEnemyWizard) == currentZone)
        {
            return nearestEnemyWizard;
        }

        if (nearestEnemy != null && nearestEnemy.getDistanceTo(wizard) <= game.getStaffRange() + nearestEnemy.getRadius())
        {
            return nearestEnemy;
        }

        if (weakestEnemyWizard != null && weakestEnemyWizard.getLife() < weakestEnemyWizard.getMaxLife() * 0.2)
        {
            double enemyHpFactor = getLifeFactor(weakestEnemyWizard);
            if (enemyHpFactor < getLifeFactor(wizard))
            {
                return weakestEnemyWizard;
            }
        }

        if (nearestEnemyWizard != null && (isEmpowered || isShielded) && wizard.getDistanceTo(nearestEnemyWizard) <= wizard.getCastRange())
        {
            return nearestEnemyWizard;
        }

        if (weakestEnemy != null && weakestEnemy != weakestEnemyWizard && wizard.getDistanceTo(weakestEnemy) <= wizard.getCastRange())
        {
            return weakestEnemy;
        }

        if (nearestEnemyWizard != null && wizard.getDistanceTo(nearestEnemyWizard) <= wizard.getCastRange())
        {
            return nearestEnemyWizard;
        }

        if (nearestEnemy != null && wizard.getDistanceTo(nearestEnemy) <= wizard.getCastRange())
        {
            return nearestEnemy;
        }

        if ((nearestNeutral != null && currentZone != null && currentZone.getId() != ZONE_BONUS_ROAD && wizard.getDistanceTo(nearestNeutral) <= game.getStaffRange() + nearestNeutral.getRadius() && StrictMath.abs(nearestNeutral.getAngleTo(wizard)) <= game.getStaffSector() / 2.0D) || (nearestNeutral != null && zoneManager.getZone(nearestNeutral).getId() == ZONE_BONUS_ROAD && currentZone.getId() == ZONE_BONUS_ROAD))
        {
            return nearestNeutral;
        }

        // Нападаем на нейтрала, только в случае, если он сам напал на нас или на одного из союзных юнитов совсем поблизости
        //        if (currentZone != null && currentZone.getId() != ZONE_BONUS_ROAD && nearestNeutral != null && nearestNeutral.getDistanceTo(wizard) <= wizard.getCastRange())
        //        {
        //            if (nearestNeutral.getDistanceTo(wizard) <= wizard.getRadius() + nearestNeutral.getRadius() + 5 && isUnitALookingAtUnitB(nearestNeutral, wizard))
        //            {
        //                return nearestNeutral;
        //            }
        //            else
        //            {
        //                for (final LivingUnit friend : nearestFriends)
        //                {
        //                    if (nearestNeutral.getDistanceTo(friend) <= friend.getRadius() + nearestNeutral.getRadius() + 5 && isUnitALookingAtUnitB(nearestNeutral, friend))
        //                    {
        //                        return nearestNeutral;
        //                    }
        //                }
        //            }
        //        }
        return null;
    }

    private int getObstaclesOnWay(final List<CircularUnit> obstacles, final CircularUnit unit, double x, double y)
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

    private void checkTargetStatuses()
    {
        if (targetEnemy == null)
            return;

        isTargetEmpowered = isTargetHastened = isTargetShielded = isTargetFrozen = isTargetBurning = false;
        for (final Status status : targetEnemy.getStatuses())
        {
            switch (status.getType())
            {
                case EMPOWERED:
                    isTargetEmpowered = true;
                    break;
                case HASTENED:
                    isTargetHastened = true;
                    break;
                case SHIELDED:
                    isTargetShielded = true;
                    break;
                case FROZEN:
                    isTargetFrozen = true;
                    break;
                case BURNING:
                    isTargetBurning = true;
                    break;
            }
        }
    }

    private void chooseLane()
    {
        currentLane = getMostFreeLane();
        waypoints = waypointsByLane.get(currentLane);
    }

    private LaneType getMostRiskyLane()
    {
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
        if (min == onTop)
            return LaneType.TOP;
        else if (min == onBottom)
            return LaneType.BOTTOM;
        else
            return LaneType.MIDDLE;
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

        public void setX(double x)
        {
            this.x = x;
        }

        public void setY(double y)
        {
            this.y = y;
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

    private static final int ZONE_WESTERN_FOREST = 6;
    private static final int ZONE_NORTHERN_FOREST = 7;
    private static final int ZONE_EASTERN_FOREST = 8;
    private static final int ZONE_SOUTHERN_FOREST = 9;

    private class ZoneManager
    {
        private final Point[] middleVertices = {new Point(400, 3200), new Point(800, 3600), new Point(3600, 800), new Point(3200, 400)};
        private final Point[] bonusRoadVertices = {new Point(400, 800), new Point(800, 400), new Point(3600, 3200), new Point(3200, 3600)};
        private final Point[] topVertices = {new Point(0, 3200), new Point(400, 3200), new Point(400, 800), new Point(800, 400), new Point(3200, 400), new Point(3200, 0), new Point(0, 0)};
        private final Point[] bottomVertices = {new Point(800, 3600), new Point(3200, 3600), new Point(3600, 3200), new Point(3600, 800), new Point(4000, 800), new Point(4000, 4000), new Point(800, 4000)};
        private final Point[] baseVertices = {new Point(0, 3200), new Point(400, 3200), new Point(800, 3600), new Point(800, 4000), new Point(0, 4000)};
        private final Point[] enemyBaseVertices = {new Point(3200, 0), new Point(4000, 0), new Point(4000, 800), new Point(3600, 800), new Point(3200, 400)};

        private final Point[] westernForestVertices = {new Point(400, 3200), new Point(400, 800), new Point(1600, 2000)};
        private final Point[] northernForestVertices = {new Point(2000, 1600), new Point(3200, 400), new Point(800, 400)};
        private final Point[] easternForestVertices = {new Point(2400, 2000), new Point(3600, 800), new Point(3600, 3200)};
        private final Point[] southernForestVertices = {new Point(2000, 2400), new Point(3200, 3600), new Point(800, 3600)};

        private Zone[] zones;
        private final Point topBonus = new Point(1200, 1200);
        private final Point bottomBonus = new Point(2800, 2800);

        public ZoneManager()
        {
            zones = new Zone[10];
            zones[0] = new Zone(ZONE_BASE, new Point(400, 3600), baseVertices);
            zones[1] = new Zone(ZONE_TOP, new Point(400, 400), topVertices);
            zones[2] = new Zone(ZONE_BOTTOM, new Point(3600, 3600), bottomVertices);
            zones[3] = new Zone(ZONE_MIDDLE, new Point(2000, 2000), middleVertices);
            zones[4] = new Zone(ZONE_BONUS_ROAD, new Point(2000, 2000), bonusRoadVertices);
            zones[5] = new Zone(ZONE_ENEMY_BASE, new Point(3600, 400), enemyBaseVertices);
            zones[6] = new Zone(ZONE_WESTERN_FOREST, new Point(1000, 2000), westernForestVertices);
            zones[7] = new Zone(ZONE_NORTHERN_FOREST, new Point(2000, 1000), northernForestVertices);
            zones[8] = new Zone(ZONE_EASTERN_FOREST, new Point(3000, 2000), easternForestVertices);
            zones[9] = new Zone(ZONE_SOUTHERN_FOREST, new Point(2000, 3000), southernForestVertices);
        }

        public Zone getZone(int id)
        {
            return zones[id];
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

        public Zone getBonusRoad()
        {
            return zones[4];
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

        public Point getTopBonusLocation()
        {
            return topBonus;
        }

        public Point getBottomBonusLocation()
        {
            return bottomBonus;
        }
    }

    private class Zone
    {
        private final int id;
        private final Point center;
        private final Point[] vertices;

        public Zone(int id, Point center, Point[] vertices)
        {
            this.id = id;
            this.center = center;
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

        public Point getCenter()
        {
            return center;
        }

        public boolean isForest()
        {
            return false;
        }
    }
}
