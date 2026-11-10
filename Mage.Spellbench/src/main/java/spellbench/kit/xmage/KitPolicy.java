package spellbench.kit.xmage;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.common.PassAbility;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.stack.StackObject;
import mage.player.ai.ComputerPlayerPolicyOnly;
import mage.player.ai.MCTSNode2;
import mage.player.ai.RemoteModelEvaluator;
import mage.player.ai.encoder.StateEncoder;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import spellbench.kit.core.Json;
import spellbench.kit.core.ObsIndex;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MageZero's policy-only player on a kit world. Every decision is {@link ComputerPlayerPolicyOnly}'s own, configured
 * as the data generator configures an {@code on_policy} player (no noise, no mulligans, the opponent's hand not
 * encoded). This class only adapts it to the kit: the world it starts on is its last-priority snapshot, its
 * priority choice is limited to the actions the decision offers, and what it chooses is recorded for the kit's plan.
 */
public class KitPolicy extends ComputerPlayerPolicyOnly {

    private static final long serialVersionUID = 1L;

    /** The model server, from {@code -Dspellbench.model.url}; one per runner process. */
    private static RemoteModelEvaluator model;

    /** One recorded live dialog answer: family and value. */
    public static final class Answer {
        public final String family;
        public final Object value;

        Answer(String family, Object value) {
            this.family = family;
            this.value = value;
        }
    }

    /** The outcome of one priority decision on one world. */
    public static final class PriorityOutcome {
        public boolean pass;
        public String reason;
        public Ability chosen;
        public Map<String, Object> semantic;
        public Map<String, Object> optionPayload;
        public Map<String, Object> executedPayload;
        public boolean activated;
        public List<Answer> answers = new ArrayList<>();
        /** The executed action did not use the stack (A1 result review, change 4). */
        public boolean nonStack;
    }

    /** True while the world is being built: nothing is decided yet. */
    public transient boolean setup = true;
    public transient boolean recording;
    public transient List<Answer> answers = new ArrayList<>();
    private transient World world;
    private transient ObsIndex obs;
    /** The priority decision whose action the next activation is. */
    private transient PriorityOutcome outcome;
    /** The next scored root is the priority decision's: keep only the offered actions. */
    private transient boolean restrictRoot;

    public KitPolicy(String name, int skill) {
        super(name, RangeOfInfluence.ALL, skill);
        // config.yml's on_policy player: the priority, target and yes/no heads on, no noise, no mulligans
        noPolicyPriority = false;
        noPolicyTarget = false;
        noPolicyUse = false;
        noNoise = true;
        allowMulligans = false;
    }

    protected KitPolicy(final KitPolicy p) {
        super(p);
        this.setup = p.setup;
        this.world = p.world;
    }

    @Override
    public KitPolicy copy() {
        return new KitPolicy(this);
    }

    static synchronized RemoteModelEvaluator model() {
        if (model == null) {
            try {
                model = new RemoteModelEvaluator(System.getProperty("spellbench.model.url", "http://127.0.0.1:50052"));
            } catch (IOException e) {
                throw new UncheckedIOException("model server unreachable", e);
            }
        }
        return model;
    }

    /** Readies the player on its built world, which is the snapshot its decisions replay from. */
    public void attach(World w) {
        world = w;
        setup = false;
        nn = model();
        stateEncoder = new StateEncoder();
        stateEncoder.setAgent(playerId);
        stateEncoder.setOpponent(w.game.getOpponent(playerId).getId());
        stateEncoder.perfectInfo = false;
        w.game.setLastPriority(playerId);
    }

    // ------------------------------------------------------------------ priority

    /** The player's priority decision, limited to the offered actions; its action is executed in the world. */
    public PriorityOutcome decidePriority(World w, ObsIndex obs) {
        PriorityOutcome out = new PriorityOutcome();
        this.obs = obs;
        restrictRoot = true;
        record(out, () -> priority(w.game));
        if (out.chosen == null) {
            out.pass = true;
            out.reason = "pass";
            out.semantic = Json.map("kind", "pass");
        }
        return out;
    }

    /** Executes a given action as the priority path executes the player's (the fixtures' forced action). */
    void execute(World w, ActivatedAbility ability, ObsIndex obs, PriorityOutcome out) {
        this.obs = obs;
        record(out, () -> activateAbility(ability, w.game));
    }

    /** Plays a game action that asks this player a selection; returns the objects it picked, in order. */
    List<Object> picks(Runnable action) {
        answers = new ArrayList<>();
        recording = true;
        try {
            action.run();
        } finally {
            recording = false;
        }
        List<Object> out = new ArrayList<>();
        for (Answer a : answers) {
            if (a.value instanceof List) {
                for (Object ref : (List<?>) a.value) {
                    if (!out.contains(ref)) { // a selection is recorded at each of its nested calls
                        out.add(ref);
                    }
                }
            }
        }
        return out;
    }

    private void record(PriorityOutcome out, Runnable decide) {
        outcome = out;
        answers = new ArrayList<>();
        recording = true;
        try {
            decide.run();
        } finally {
            recording = false;
            outcome = null;
            restrictRoot = false;
        }
        out.answers = answers;
    }

    @Override
    public UUID bestAction(MCTSNode2 node, Game game) {
        if (restrictRoot) {
            restrictRoot = false;
            node.getChildren().removeIf(child -> !offered(child.getActionId(game), game));
        }
        return super.bestAction(node, game);
    }

    /** An action is offered when its v2 semantic is one of the decision's candidates; pass always is. */
    private boolean offered(UUID id, Game game) {
        for (ActivatedAbility a : getPlayableAbilities(game)) {
            if (a.getId().equals(id)) {
                return a instanceof PassAbility || KitContext.rootFilter == null || KitContext.rootFilter.test(a, game);
            }
        }
        return true; // the root's own pass
    }

    @Override
    public boolean activateAbility(ActivatedAbility ability, Game game) {
        PriorityOutcome out = outcome;
        if (out == null || ability instanceof PassAbility) {
            return super.activateAbility(ability, game);
        }
        outcome = null; // activations inside this one (mana) belong to it
        out.chosen = ability;
        out.semantic = Mapping.prioritySemantic(world, game, ability, obs);
        out.optionPayload = Mapping.payload(world, ability, game);
        int before = game.getStack().size();
        out.activated = super.activateAbility(ability, game);
        if (out.activated && game.getStack().size() > before) {
            StackObject top = game.getStack().getFirstOrNull();
            out.executedPayload = Mapping.payload(world, Mapping.executedAbility(top), game);
        } else {
            // an action that does not use the stack leaves no executed object: what it asked is carried by no copy
            out.nonStack = out.activated;
            out.executedPayload = new LinkedHashMap<>();
        }
        return out.activated;
    }

    // ------------------------------------------------------------------ world setup and recording

    @Override
    protected boolean makeChoice(Outcome outcome, Target target, Ability source, Game game, Cards fromCards) {
        return setup ? makeChoiceFallback(outcome, target, source, game, fromCards)
                : super.makeChoice(outcome, target, source, game, fromCards);
    }

    @Override
    public boolean chooseMulligan(Game game) {
        return !setup && super.chooseMulligan(game);
    }

    private void record(Game game, String family, Object value) {
        if (recording && game != null && !game.isSimulation()) {
            answers.add(new Answer(family, value));
        }
    }

    private List<Object> ids(Target t) {
        List<Object> out = new ArrayList<>();
        for (UUID id : t.getTargets()) {
            out.add(Mapping.targetRef(world, id));
        }
        return out;
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        boolean r = super.chooseTarget(outcome, target, source, game);
        record(game, "target", ids(target));
        return r;
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        if (setup) {
            return false;
        }
        boolean r = super.choose(outcome, target, source, game, options);
        record(game, "select", ids(target));
        return r;
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        boolean r = super.chooseTarget(outcome, cards, target, source, game);
        record(game, "target", ids(target));
        return r;
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        boolean r = super.choose(outcome, cards, target, source, game);
        record(game, "select", ids(target));
        return r;
    }

    @Override
    public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
        boolean r = super.chooseTargetAmount(outcome, target, source, game);
        record(game, "target_amount", ids(target));
        return r;
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText,
                             Ability source, Game game) {
        if (setup) {
            return false;
        }
        boolean r = super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game);
        record(game, "use", r);
        return r;
    }

    @Override
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        Mode m = super.chooseMode(modes, source, game);
        if (m != null) {
            record(game, "mode", (long) new ArrayList<>(modes.keySet()).indexOf(m.getId()));
        }
        return m;
    }

    @Override
    public int announceX(int min, int max, String message, Game game, Ability source, boolean isManaPay) {
        int x = super.announceX(min, max, message, game, source, isManaPay);
        record(game, "x", (long) x);
        return x;
    }

    @Override
    public int getAmount(int min, int max, String message, Ability source, Game game) {
        int x = super.getAmount(min, max, message, source, game);
        record(game, "amount", (long) x);
        return x;
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        boolean r = super.choose(outcome, choice, game);
        record(game, "choice", choice.isKeyChoice() ? choice.getChoiceKey() : choice.getChoice());
        return r;
    }
}
