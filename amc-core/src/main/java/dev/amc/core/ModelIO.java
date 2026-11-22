package dev.amc.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stores only trained weights; optimizer state and configuration must be supplied separately. */
public final class ModelIO {
    private static final int MAGIC=0x414D4331; // AMC1
    private ModelIO() {}
    public static void save(AmcModel model, Path path) throws IOException {
        if(path.getParent()!=null) Files.createDirectories(path.getParent());
        try(var out=new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(MAGIC);
            out.writeInt(model.parameterCount());
            for(Scalar p:model.parameters()) out.writeDouble(p.value);
        }
    }
    public static void load(AmcModel model, Path path) throws IOException {
        var parameters = model.parameters();
        double[] weights = new double[parameters.size()];
        try(var in=new DataInputStream(Files.newInputStream(path))) {
            if(in.readInt()!=MAGIC) throw new IOException("Invalid AMC checkpoint");
            if(in.readInt()!=model.parameterCount()) throw new IOException("Parameter count mismatch");
            for (int i = 0; i < weights.length; i++) {
                weights[i] = in.readDouble();
                if (!Double.isFinite(weights[i])) throw new IOException("Non-finite weight in checkpoint");
            }
            if(in.read()!=-1) throw new IOException("Trailing bytes in checkpoint");
        }
        for (int i = 0; i < weights.length; i++) parameters.get(i).value = weights[i];
    }
}
