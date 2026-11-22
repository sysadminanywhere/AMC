package dev.amc.core;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Minimal reverse-mode scalar autodifferentiation. For correctness experiments, not production-scale training. */
public final class Scalar {
    public double value;
    public double grad;
    private final Scalar[] parents;
    private final Runnable backward;

    private Scalar(double value, Scalar[] parents, Runnable backward) {
        this.value = value;
        this.parents = parents;
        this.backward = backward;
    }
    public static Scalar of(double value) { return new Scalar(value, new Scalar[0], () -> {}); }
    public Scalar plus(Scalar b) {
        Scalar a = this;
        Scalar[] output = new Scalar[1];
        output[0] = new Scalar(value + b.value, new Scalar[]{a,b},
                () -> { a.grad += output[0].grad; b.grad += output[0].grad; });
        return output[0];
    }
    public Scalar plus(double b) { return plus(of(b)); }
    public Scalar times(Scalar b) {
        Scalar a = this;
        Scalar[] output = new Scalar[1];
        output[0] = new Scalar(value * b.value, new Scalar[]{a,b},
                () -> { a.grad += b.value * output[0].grad; b.grad += a.value * output[0].grad; });
        return output[0];
    }
    public Scalar times(double b) { return times(of(b)); }
    public Scalar neg() { return times(-1); }
    public Scalar minus(Scalar b) { return plus(b.neg()); }
    public Scalar tanh() {
        Scalar a = this;
        double t = Math.tanh(value);
        Scalar[] output = new Scalar[1];
        output[0] = new Scalar(t, new Scalar[]{a}, () -> a.grad += (1-t*t) * output[0].grad);
        return output[0];
    }
    public Scalar exp() {
        Scalar a = this;
        double exp = Math.exp(value);
        Scalar[] output = new Scalar[1];
        output[0] = new Scalar(exp, new Scalar[]{a}, () -> a.grad += exp * output[0].grad);
        return output[0];
    }
    public Scalar log() {
        Scalar a = this;
        Scalar[] output = new Scalar[1];
        output[0] = new Scalar(Math.log(value), new Scalar[]{a}, () -> a.grad += output[0].grad / a.value);
        return output[0];
    }
    public void backward() {
        List<Scalar> topo = new ArrayList<>();
        IdentityHashMap<Scalar,Boolean> seen = new IdentityHashMap<>();
        visit(this, seen, topo);
        for (Scalar node : topo) node.grad = 0;
        this.grad = 1;
        for (int i=topo.size()-1; i>=0; i--) topo.get(i).backward.run();
    }
    private static void visit(Scalar x, IdentityHashMap<Scalar,Boolean> seen, List<Scalar> topo) {
        if (seen.put(x, Boolean.TRUE) != null) return;
        for (Scalar parent : x.parents) visit(parent, seen, topo);
        topo.add(x);
    }
}
