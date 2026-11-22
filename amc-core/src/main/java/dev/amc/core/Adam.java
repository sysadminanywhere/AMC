package dev.amc.core;

import java.util.IdentityHashMap;
import java.util.List;

public final class Adam {
    private final List<Scalar> parameters;
    private final IdentityHashMap<Scalar,double[]> state = new IdentityHashMap<>();
    private final double learningRate;
    private int step;
    public Adam(List<Scalar> parameters, double learningRate) {
        this.parameters=List.copyOf(parameters);
        this.learningRate=learningRate;
        for(var p : parameters) state.put(p, new double[2]);
    }
    public void update() {
        step++;
        double correction1=1-Math.pow(0.9, step), correction2=1-Math.pow(0.999, step);
        for(var p : parameters) {
            double g=Math.max(-5,Math.min(5,p.grad));
            double[] s=state.get(p);
            s[0]=0.9*s[0]+0.1*g;
            s[1]=0.999*s[1]+0.001*g*g;
            p.value-=learningRate*(s[0]/correction1)/(Math.sqrt(s[1]/correction2)+1e-8);
        }
    }
}
