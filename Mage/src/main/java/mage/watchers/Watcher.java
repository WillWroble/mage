package mage.watchers;

import mage.constants.WatcherScope;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.util.CardUtil;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.lang.reflect.*;
import java.util.*;

/**
 * watches for certain game events to occur and flags condition
 *
 * @author BetaSteward_at_googlemail.com
 */
public abstract class Watcher implements Serializable {

    private static final Logger logger = Logger.getLogger(Watcher.class);

    protected UUID controllerId;
    protected UUID sourceId;
    protected boolean condition;
    protected final WatcherScope scope;

    public Watcher(WatcherScope scope) {
        this.scope = scope;
    }

    protected Watcher(final Watcher watcher) {
        this.condition = watcher.condition;
        this.controllerId = watcher.controllerId;
        this.sourceId = watcher.sourceId;
        this.scope = watcher.scope;
    }

    public UUID getControllerId() {
        return controllerId;
    }

    public void setControllerId(UUID controllerId) {
        this.controllerId = controllerId;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public void setSourceId(UUID sourceId) {
        this.sourceId = sourceId;
    }

    public String getKey() {
        switch (scope) {
            case GAME:
                return getBasicKey();
            case PLAYER:
                return controllerId + getBasicKey();
            case CARD:
                return sourceId + getBasicKey();
            default:
                throw new IllegalArgumentException("Unknown watcher scope: " + this.getClass().getSimpleName() + " - " + scope);
        }
    }

    public boolean conditionMet() {
        return condition;
    }

    public void reset() {
        condition = false;
    }

    protected String getBasicKey() {
        return getClass().getSimpleName();
    }

    public abstract void watch(GameEvent event, Game game);

    // reflection lookups for copy(), done once per watcher class
    private static final ClassValue<CopyPlan> COPY_PLANS = new ClassValue<CopyPlan>() {
        @Override
        protected CopyPlan computeValue(Class<?> type) {
            return new CopyPlan(type);
        }
    };

    private static final class CopyPlan {

        private final Constructor<?> constructor; // null if the class has multiple constructors
        private final Object[] args;
        private final List<Field> fields = new ArrayList<>();

        private CopyPlan(Class<?> type) {
            //use getDeclaredConstructors to allow for package-private constructors (i.e. omit public)
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length > 1) {
                constructor = null;
                args = null;
                return;
            }
            constructor = constructors[0];
            constructor.setAccessible(true);
            args = new Object[constructor.getParameterCount()];
            for (int index = 0; index < constructor.getParameterTypes().length; index++) {
                Class<?> parameterType = constructor.getParameterTypes()[index];
                if (parameterType.isPrimitive()) {
                    if (parameterType.getSimpleName().equalsIgnoreCase("boolean")) {
                        args[index] = false;
                    }
                } else {
                    args[index] = null;
                }
            }
            List<Field> allFields = new ArrayList<>();
            allFields.addAll(Arrays.asList(type.getDeclaredFields()));
            allFields.addAll(Arrays.asList(type.getSuperclass().getDeclaredFields()));
            for (Field field : allFields) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
        }
    }

    public <T extends Watcher> T copy() {
        try {
            CopyPlan plan = COPY_PLANS.get(getClass());
            if (plan.constructor == null) {
                logger.error(getClass().getSimpleName() + " has multiple constructors");
                return null;
            }
            T watcher = (T) plan.constructor.newInstance(plan.args);

            // copy field's values
            for (Field field : plan.fields) {
                field.set(watcher, CardUtil.deepCopyObject(field.get(this)));
            }
            return watcher;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            logger.error("Can't copy watcher: " + e.getMessage(), e);
        }
        return null;
    }

    public WatcherScope getScope() {
        return scope;
    }
}
