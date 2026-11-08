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
import mage.game.events.GameEvent;
import mage.game.stack.StackObject;
import mage.player.ai.ComputerPlayerPolicyOnly;
import mage.player.ai.RemoteModelEvaluator;
import mage.player.ai.encoder.ActionEncoder.ActionType;
import mage.player.ai.encoder.FeatureGraph;
import mage.player.ai.encoder.StateEncoder;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import spellbench.kit.core.Json;
import spellbench.kit.core.ObsIndex;
import spellbench.kit.core.Offers;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static mage.target.TargetImpl.STOP_CHOOSING;

/**
 * MageZero's policy-only player (greedy, no search) deciding on one kit world. Each decision is encoded the way
 * {@code MCTSPlayer} encodes it in training (same decision text, options and source), scored by the model once, and
 * answered with the highest-scoring legal choice. The opponent's hand is never encoded ({@code perfectInfo} false):
 * the world's opponent hand is a sample, and only its size is public.
 * <p>
 * Make-choice and number dialogs have no policy head and use the {@code ComputerPlayer} heuristics, as in
 * {@link ComputerPlayerPolicyOnly}. Replaces the kit's MAD decider ({@code KitMad}); its recording of live dialog
 * answers and its priority outcome are kept unchanged.
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

    public transient boolean setup = true;
    public transient boolean recording;
    public transient List<Answer> answers = new ArrayList<>();
    private transient World world;

    public KitPolicy(String name, int skill) {
        super(name, RangeOfInfluence.ALL, skill);
        noNoise = true;
        allowMulligans = false;
        offlineMode = false;
    }

    protected KitPolicy(final KitPolicy p) {
        super(p);
        this.setup = p.setup;
        this.recording = false;
        this.answers = new ArrayList<>();
        this.world = p.world;
    }

    @Override
    public KitPolicy copy() {
        return new KitPolicy(this);
    }

    public void attach(World w) {
        this.world = w;
        this.setup = false;
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

    /** Encodes this decision as training does and scores it; fails closed when the model gives no answer. */
    private RemoteModelEvaluator.InferenceResult infer(Game game, ActionType type, String text, Cards options, UUID source) {
        if (stateEncoder == null) {
            stateEncoder = new StateEncoder();
            stateEncoder.perfectInfo = false;
            stateEncoder.setAgent(playerId);
            stateEncoder.setOpponent(game.getOpponent(playerId).getId());
        }
        FeatureGraph state = stateEncoder.processState(game, playerId, type, text, options, source);
        RemoteModelEvaluator.InferenceResult out = model().infer(state);
        if (out == null) {
            throw new IllegalStateException("model returned no result for " + type);
        }
        return out;
    }

    /** The first id with the highest score; ids the model did not score are never chosen. */
    private static UUID argmax(Collection<UUID> ids, Map<UUID, Float> scores) {
        UUID best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (UUID id : ids) {
            Float s = scores == null ? null : scores.get(id);
            if (s != null && (best == null || s > bestScore)) {
                best = id;
                bestScore = s;
            }
        }
        if (best == null) {
            throw new IllegalStateException("model scored none of " + ids.size() + " options");
        }
        return best;
    }

    /** The outcome of one priority decision on one world. */
    public static final class PriorityOutcome {
        public boolean pass;
        public String reason;
        public Ability chosen;
        public Map<String, Object> semantic;
        public Map<String, Object> optionPayload;
        public Map<String, Object> executedPayload;
        public List<RootStat> stats = new ArrayList<>();
        public List<Map<String, Object>> statSemantics = new ArrayList<>();
        public List<Map<String, Object>> statPayloads = new ArrayList<>();
        public boolean activated;
        public List<Answer> answers = new ArrayList<>();
        /** The executed action did not use the stack (A1 result review, change 4). */
        public boolean nonStack;
    }

    /**
     * The policy's priority choice among the playable actions the decision offers (the runner's root filter); with
     * {@code act}, the action is executed in the world and its payload read from the executed object.
     */
    public PriorityOutcome decidePriority(World w, ObsIndex obs, boolean act) {
        Game game = w.game;
        PriorityOutcome out = new PriorityOutcome();
        game.getState().setPriorityPlayerId(playerId);
        game.firePriorityEvent(playerId);
        List<ActivatedAbility> offered = new ArrayList<>();
        for (ActivatedAbility a : getPlayableAbilities(game)) {
            if (a instanceof PassAbility || KitContext.rootFilter == null || KitContext.rootFilter.test(a, game)) {
                offered.add(a);
            }
        }
        // ties go to the earlier candidate (settled: candidate order, never hash order)
        offered.sort(Comparator.comparingInt(a -> candidateIndex(w, game, a, obs)));
        RemoteModelEvaluator.InferenceResult scores = infer(game, ActionType.PRIORITY, "priority", null, null);
        UUID bestId = argmax(offered.stream().map(Ability::getId).collect(Collectors.toList()), scores.policyPriority);
        Ability best = offered.stream().filter(a -> a.getId().equals(bestId)).findFirst().get();
        if (best instanceof PassAbility) {
            out.pass = true;
            out.reason = "pass";
            out.semantic = Json.map("kind", "pass");
            return out;
        }
        out.chosen = best;
        out.semantic = Mapping.prioritySemantic(w, game, best, obs);
        out.optionPayload = Mapping.payload(w, best, game);
        if (act) {
            execute(w, best, out);
        }
        return out;
    }

    private static int candidateIndex(World w, Game game, Ability a, ObsIndex obs) {
        if (a instanceof PassAbility) {
            return -1;
        }
        int c = KitContext.candidates == null ? -1 : Offers.candidateFor(KitContext.candidates, Mapping.prioritySemantic(w, game, a, obs));
        return c < 0 ? Integer.MAX_VALUE : c;
    }

    /**
     * A target dialog's options in a deterministic order: a synthesized dialog's (a {@link KitTarget}) keeps the
     * decision's candidate order; a dialog the world asks while an action executes is ordered by v2 reference.
     */
    private List<UUID> ordered(Target target, Set<UUID> possible) {
        List<UUID> out = new ArrayList<>(possible);
        if (!(target instanceof KitTarget)) {
            out.sort(Comparator.comparing(id -> world == null ? id.toString() : String.valueOf(Mapping.targetRef(world, id))));
        }
        return out;
    }

    /** Activates the chosen action, recording the live dialogs and the executed payload (as {@code KitMad}). */
    void execute(World w, Ability ability, PriorityOutcome out) {
        Game game = w.game;
        for (Target target : ability.getTargets()) {
            for (UUID id : target.getTargets()) {
                target.updateTarget(id, game);
                if (!target.isNotTarget()) {
                    game.addSimultaneousEvent(GameEvent.getEvent(GameEvent.EventType.TARGETED, id, ability, ability.getControllerId()));
                }
            }
        }
        int before = game.getStack().size();
        answers = new ArrayList<>();
        recording = true;
        try {
            out.activated = this.activateAbility((ActivatedAbility) ability, game);
        } finally {
            recording = false;
        }
        out.answers = answers;
        if (out.activated && game.getStack().size() > before) {
            StackObject top = game.getStack().getFirstOrNull();
            out.executedPayload = Mapping.payload(w, Mapping.executedAbility(top), game);
        } else {
            // an action that does not use the stack leaves no executed object: what it asked is carried by no copy
            out.nonStack = out.activated;
            out.executedPayload = new java.util.LinkedHashMap<>();
        }
    }

    // ------------------------------------------------------------------ policy dialogs

    @Override
    protected boolean makeChoice(Outcome outcome, Target target, Ability source, Game game, Cards fromCards) {
        if (setup || game.isSimulation()) {
            return makeChoiceFallback(outcome, target, source, game, fromCards);
        }
        if (fromCards != null && fromCards.isEmpty()) {
            return false;
        }
        UUID controller = target.getAffectedAbilityControllerId(getId());
        if (target.isChoiceCompleted(controller, source, game, fromCards)) {
            return false;
        }
        Set<UUID> possible = target.possibleTargets(controller, source, game, fromCards).stream()
                .filter(id -> !target.contains(id)).collect(Collectors.toCollection(LinkedHashSet::new));
        if (possible.isEmpty()) {
            return false;
        }
        UUID choice;
        if (possible.size() + (target.isChosen(game) ? 1 : 0) == 1) {
            choice = possible.iterator().next();
        } else {
            List<UUID> options = ordered(target, possible);
            if (target.isChosen(game)) {
                options.add(STOP_CHOOSING);
            }
            String text = (source == null ? "null" : source.getRule()) + ":Choose a target:" + target.getTargetName();
            choice = argmax(options, infer(game, ActionType.CHOOSE_TARGET, text, fromCards,
                    source == null ? null : source.getSourceId()).policyTarget);
        }
        if (choice.equals(STOP_CHOOSING)) {
            return target.isChosen(game);
        }
        target.addTarget(choice, source, game);
        if (target.contains(choice)) {
            makeChoice(outcome, target, source, game, fromCards);
        }
        return target.isChosen(game);
    }

    private boolean policyUse(String message, Ability source, Game game) {
        RemoteModelEvaluator.InferenceResult scores = infer(game, ActionType.CHOOSE_USE, message, null,
                source == null ? null : source.getSourceId());
        List<UUID> options = new ArrayList<>();
        options.add(FeatureGraph.USE_FALSE_ID);
        options.add(FeatureGraph.USE_TRUE_ID);
        return argmax(options, scores.policyUse).equals(FeatureGraph.USE_TRUE_ID);
    }

    // ------------------------------------------------------------------ setup and recording

    @Override
    public boolean chooseMulligan(Game game) {
        if (setup) {
            return false;
        }
        return super.chooseMulligan(game);
    }

    private boolean live(Game game) {
        return recording && game != null && !game.isSimulation();
    }

    private void record(Game game, String family, Object value) {
        if (live(game)) {
            answers.add(new Answer(family, value));
        }
    }

    private static List<Object> ids(World w, Target t) {
        List<Object> out = new ArrayList<>();
        for (UUID id : t.getTargets()) {
            out.add(w == null ? id.toString() : Mapping.targetRef(w, id));
        }
        return out;
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        boolean r = super.chooseTarget(outcome, target, source, game);
        record(game, "target", ids(world, target));
        return r;
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        if (setup) {
            return false;
        }
        boolean r = super.choose(outcome, target, source, game, options);
        record(game, "select", ids(world, target));
        return r;
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        boolean r = super.chooseTarget(outcome, cards, target, source, game);
        record(game, "target", ids(world, target));
        return r;
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        boolean r = super.choose(outcome, cards, target, source, game);
        record(game, "select", ids(world, target));
        return r;
    }

    @Override
    public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
        boolean r = super.chooseTargetAmount(outcome, target, source, game);
        record(game, "target_amount", ids(world, target));
        return r;
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText,
                             Ability source, Game game) {
        if (setup) {
            return false;
        }
        boolean r = game.isSimulation() ? false : policyUse(message, source, game);
        record(game, "use", r);
        return r;
    }

    @Override
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        Mode m = super.chooseMode(modes, source, game);
        if (m != null) {
            long index = 0;
            for (Mode x : modes.values()) {
                if (x.getId().equals(m.getId())) {
                    break;
                }
                index++;
            }
            record(game, "mode", index);
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
