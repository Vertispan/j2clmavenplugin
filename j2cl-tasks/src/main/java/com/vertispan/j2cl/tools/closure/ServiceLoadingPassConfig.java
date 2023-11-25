package com.vertispan.j2cl.tools.closure;

import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DefaultPassConfig;
import com.google.javascript.jscomp.PassConfig;
import com.google.javascript.jscomp.PassFactory;
import com.google.javascript.jscomp.PassListBuilder;
import com.google.javascript.jscomp.PassNames;

public class ServiceLoadingPassConfig extends PassConfig.PassConfigDelegate {

    private final PassFactory convertServiceLoaderProperties =
            PassFactory.builder()
                    .setName("ConvertServiceLoaderProperties")
                    .setRunInFixedPointLoop(true)
                    .setInternalFactory(ConvertServiceLoaderProperties::new)
                    .build();

    public ServiceLoadingPassConfig(CompilerOptions options) {
        super(new DefaultPassConfig(options));
    }

    @Override
    protected PassListBuilder getOptimizations() {
        PassListBuilder optimizations = super.getOptimizations();
//        optimizations.addAfter(convertServiceLoaderProperties, PassNames.PARSE_INPUTS);
        optimizations.addAfter(convertServiceLoaderProperties, PassNames.NORMALIZE);
        optimizations.addBefore(convertServiceLoaderProperties, PassNames.AFTER_EARLY_OPTIMIZATION_LOOP);
        optimizations.addBefore(convertServiceLoaderProperties, PassNames.AFTER_MAIN_OPTIMIZATIONS);
//        optimizations.addAfter(convertServiceLoaderProperties, PassNames.PEEPHOLE_OPTIMIZATIONS);
//        optimizations.findByName()
        return optimizations;
    }
}
