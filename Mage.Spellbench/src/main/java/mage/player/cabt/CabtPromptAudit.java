package mage.player.cabt;

import java.util.ArrayList;
import java.util.List;

/**
 * Coverage view over {@link CabtDecisionSurfaceAudit}: filters entries by
 * status and resolves their implementation/test class names on the
 * classpath.
 * <p>
 * This is what makes the audit enforceable rather than documentary — the
 * accompanying test fails the suite when a SURFACED prompt lacks a resolvable
 * implementation or test class, so new XMage callbacks and prompt families
 * can only be added as audit entry + implementation + tests together.
 */
public final class CabtPromptAudit {

    private CabtPromptAudit() {
    }

    public static List<CabtDecisionSurface> byStatus(CabtDecisionSurfaceStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        List<CabtDecisionSurface> matching = new ArrayList<CabtDecisionSurface>();
        for (CabtDecisionSurface entry : CabtDecisionSurfaceAudit.entries()) {
            if (entry.getStatus() == status) {
                matching.add(entry);
            }
        }
        return matching;
    }

    /**
     * Active Player prompt callbacks the engine can call on the bridge —
     * everything from the Player interface except pure query references.
     */
    public static List<CabtDecisionSurface> playerCallbackEntries() {
        List<CabtDecisionSurface> callbacks = new ArrayList<CabtDecisionSurface>();
        for (CabtDecisionSurface entry : CabtDecisionSurfaceAudit.entries()) {
            if (entry.getSource() == CabtDecisionSurfaceSource.PLAYER_INTERFACE
                    && entry.getStatus() != CabtDecisionSurfaceStatus.REFERENCE_ONLY) {
                callbacks.add(entry);
            }
        }
        return callbacks;
    }

    public static boolean classExists(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        try {
            Class.forName(className, false, CabtPromptAudit.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
