package mage.player.ai;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.ComputerPlayer7;

public class ComputerPlayerRL extends ComputerPlayer7{
    public ComputerPlayerRL(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }
    public ComputerPlayerRL(final ComputerPlayerRL player) {
        super(player);
    }
    @Override
    public boolean priority(Game game) {
        System.out.println("RL active");
        return super.priority(game);
    }

}
