package mage.players;

import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.game.combat.Combat;

import java.util.*;


/**
 * represents a sequence of game actions for a player. These include priority abilities, mode decisions, targeting decisions, and other discrete choices (choose color, choose type, etc).
 */
public class PlayerScript {
    //for targeting decisions
    public ArrayDeque<UUID> targetSequence;
    //for discrete choices
    public ArrayDeque<String> choiceSequence;
    //binary decisions
    public ArrayDeque<Boolean> useSequence;
    //for priority abilities
    public ArrayDeque<Ability> prioritySequence;
    //for mode decisions
    public ArrayDeque<Mode> modeSequence;


    public PlayerScript() {
        prioritySequence = new ArrayDeque<>();
        useSequence = new ArrayDeque<>();
        targetSequence = new ArrayDeque<>();
        choiceSequence = new ArrayDeque<>();
        modeSequence =  new ArrayDeque<>();

    }
    public PlayerScript(List<Ability> prio, List<Mode> mode, List<UUID> target, List<String> choice, List<Boolean> use) {
        prioritySequence = new ArrayDeque<>(prio);
        for(Ability a : prio) {//need to copy ability for certain edge cases unfortunately
            this.prioritySequence.add(a.copy());
        }
        useSequence = new ArrayDeque<>(use);
        targetSequence = new ArrayDeque<>(target);
        choiceSequence = new ArrayDeque<>(choice);
        modeSequence = new ArrayDeque<>(mode);
    }

    public PlayerScript(PlayerScript playerScript) {
        //prioritySequence = new ArrayDeque<>(playerScript.prioritySequence);
        prioritySequence = new ArrayDeque<>();
        for(Ability a : playerScript.prioritySequence) {//need to copy ability for certain edge cases unfortunately
            this.prioritySequence.add(a.copy());
        }
        useSequence = new ArrayDeque<>(playerScript.useSequence);
        targetSequence = new ArrayDeque<>(playerScript.targetSequence);
        choiceSequence = new ArrayDeque<>(playerScript.choiceSequence);
        modeSequence = new ArrayDeque<>(playerScript.modeSequence);
    }

    public PlayerScript append(PlayerScript playerScript) {
        this.targetSequence.addAll(playerScript.targetSequence);
        this.choiceSequence.addAll(playerScript.choiceSequence);
        this.useSequence.addAll(playerScript.useSequence);
        this.modeSequence.addAll(playerScript.modeSequence);
        //this.prioritySequence.addAll(playerScript.prioritySequence);
        for(Ability a : playerScript.prioritySequence) {//need to copy ability for certain edge cases unfortunately
            this.prioritySequence.add(a.copy());
        }
        return this;
    }
    public void clear() {
        this.targetSequence.clear();
        this.choiceSequence.clear();
        this.useSequence.clear();
        this.prioritySequence.clear();
        this.modeSequence.clear();
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlayerScript)) return false;
        PlayerScript that = (PlayerScript) o;

        return dequeEquals(targetSequence, that.targetSequence, Objects::equals)
                && dequeEquals(choiceSequence,  that.choiceSequence,  Objects::equals)
                && dequeEquals(useSequence, that.useSequence, Objects::equals)
                && dequeEquals(modeSequence, that.modeSequence, Objects::equals)
                // Use a stable key for Ability/Combat instead of reference equality
                && dequeEquals(prioritySequence, that.prioritySequence,
                (a,b) -> Objects.equals(abilityKey(a), abilityKey(b)));

    }

    private static <T> boolean dequeEquals(Deque<T> a, Deque<T> b, java.util.function.BiPredicate<T,T> eq) {
        if (a.size() != b.size()) return false;
        Iterator<T> i = a.iterator(), j = b.iterator();
        while (i.hasNext()) if (!eq.test(i.next(), j.next())) return false;
        return true;
    }

    private static String abilityKey(Ability ab) {
        if (ab == null) return "null";
        // pick a stable identity for your matching purposes
        return ab.getClass().getName() + '|' + ab.getSourceId() + '|' + ab.getRule();
        // if modes/targets matter, incorporate them here too
    }

    private static String combatKey(Combat c) {
        if (c == null) return "null";
        // summarize attackers/defenders+taps in a deterministic order
        // (or if your Combat has a stable toString you trust, you can use that)
        return c.toString();
    }

    @Override
    public int hashCode() {
        int h = 1;
        h = 31*h + hashDeque(targetSequence, Objects::hashCode);
        h = 31*h + hashDeque(choiceSequence,  Objects::hashCode);
        h = 31*h + hashDeque(modeSequence, Objects::hashCode);
        h = 31*h + hashDeque(useSequence,  Objects::hashCode);
        h = 31*h + hashDeque(prioritySequence, ab -> Objects.hash(abilityKey(ab)));
        return h;
    }

    private static <T> int hashDeque(Deque<T> d, java.util.function.ToIntFunction<T> hasher) {
        int h = 1;
        for (T e : d) h = 31*h + hasher.applyAsInt(e);
        return h;
    }

    @Override
    public String toString() {
        return "PlayerScript{targets=" + targetSequence
                + ", choices=" + choiceSequence
                + ", uses=" + useSequence
                + ", modes=" + modeSequence
                + ", priority=" + prioritySequence
                 + "}";
    }
}
