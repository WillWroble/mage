package mage.player.cabt;

/**
 * One entry in the decision-surface audit: an XMage prompt callback, query
 * API, or design reference, with where it comes from, how the bridge treats
 * it today, which classes implement and test it, and the plan for surfacing
 * it.
 * <p>
 * {@code implementationClass} and {@code testClass} are fully-qualified class
 * names; {@link CabtPromptAudit} resolves them on the classpath so the test
 * suite fails when a surfaced prompt loses its implementation or test
 * coverage. Both are empty (never null) for reference-only entries.
 * {@code adapterPlan} doubles as the expected-current-behavior description.
 */
public final class CabtDecisionSurface {

    private final String name;
    private final CabtDecisionSurfaceSource source;
    private final CabtDecisionSurfaceStatus status;
    private final String implementationClass;
    private final String testClass;
    private final String xmageReference;
    private final String adapterPlan;
    private final String testPlan;

    public CabtDecisionSurface(String name,
                               CabtDecisionSurfaceSource source,
                               CabtDecisionSurfaceStatus status,
                               String implementationClass,
                               String testClass,
                               String xmageReference,
                               String adapterPlan,
                               String testPlan) {
        if (name == null || source == null || status == null
                || implementationClass == null || testClass == null
                || xmageReference == null || adapterPlan == null || testPlan == null) {
            throw new IllegalArgumentException("decision surface fields must not be null");
        }
        this.name = name;
        this.source = source;
        this.status = status;
        this.implementationClass = implementationClass;
        this.testClass = testClass;
        this.xmageReference = xmageReference;
        this.adapterPlan = adapterPlan;
        this.testPlan = testPlan;
    }

    public String getName() {
        return name;
    }

    public CabtDecisionSurfaceSource getSource() {
        return source;
    }

    public CabtDecisionSurfaceStatus getStatus() {
        return status;
    }

    public String getImplementationClass() {
        return implementationClass;
    }

    public String getTestClass() {
        return testClass;
    }

    public String getXmageReference() {
        return xmageReference;
    }

    public String getAdapterPlan() {
        return adapterPlan;
    }

    public String getTestPlan() {
        return testPlan;
    }
}
