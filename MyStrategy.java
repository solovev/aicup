import model.*;

public final class MyStrategy implements Strategy
{
    boolean alreadyPrint = false;
    @Override
    public void move(Wizard self, World world, Game game, Move move)
    {
//        long startTime = System.currentTimeMillis();
//        DebugHelper.initialize();
//        DebugHelper.beginPost();
//
//        if (!alreadyPrint)
//        {
//            System.out.println(game.getRandomSeed());
//            alreadyPrint = true;
//        }

        Behaviour.getInstance().handleTick(self, world, game, move);
//
//        long stopTime = System.currentTimeMillis();
//        long elapsedTime = stopTime - startTime;
//
//        DebugHelper.addLabel("Frame time", elapsedTime);
//        DebugHelper.addLabel("Random seed", game.getRandomSeed());
//        DebugHelper.sendLabels();
//        DebugHelper.endPost();
    }
}
